/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentruntime.memory.compaction;

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.domain.Span;
import com.agentruntime.api.domain.StructuredExtraction;
import com.agentruntime.api.domain.SummaryContent;
import com.agentruntime.api.domain.SummarySnapshot;
import com.agentruntime.api.enums.CompactionFailureKindEnum;
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.SpanTypeEnum;
import com.agentruntime.api.enums.SummaryStatusEnum;
import com.agentruntime.api.enums.ToolCompactionStrategyEnum;
import com.agentruntime.api.memory.CompactConfig;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.api.repository.MessageRepository;
import com.agentruntime.api.repository.SummarySnapshotRepository;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.core.springai.MemoryBudgetContextHolder;
import com.agentruntime.memory.budget.BudgetCalculator;
import com.agentruntime.memory.budget.BudgetResult;
import com.agentruntime.memory.chat.CompactionMessage;
import com.agentruntime.memory.history.SessionHistory;
import com.agentruntime.memory.redis.RedisCompareLock;
import com.agentruntime.memory.write.MemoryWriteQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 摘要压缩协调器：在异步线程内对一段对话历史做分层压缩，持久化为新版本 {@link SummarySnapshot}。
 * per-session Redis 锁保证分布式下同一会话同时只有一个压缩任务。
 * 锁在压缩期间按 TTL 的三分之一续期，避免分批摘要超过 TTL 后第二个任务进入。
 * 快照版本号在数据库里取 MAX+1，不使用读出来的 version+1。
 *
 * <p>流程：加锁 → double-check 预算 → 划定候选段 → {@link TurnSelector} 打分截断 →
 * 可选 {@link StructuredExtractor}（失败标 DEGRADED 继续）→ {@link ChatHistorySummarizer}
 * （上下文不足分批合并；非法 JSON 重试后仍失败则 FAILED）→ 第四层校验 → 保存快照。
 * FAILED 不覆盖旧摘要、不推进边界，只记 {@link EventTypeEnum#COMPACTION_FAILED}。
 */
public class SummaryCompactor {

    private static final Logger log = LoggerFactory.getLogger(SummaryCompactor.class);

    private static final String LOCK_PREFIX = "agentruntime:summarylock:";

    private final SummarySnapshotRepository summaryRepository;
    private final MessageRepository messageRepository;
    private final MemoryWriteQueue writeQueue;
    private final BudgetCalculator budgetCalculator;
    private final TokenEstimator tokenEstimator;
    private final CompactConfig config;
    private final ObjectMapper objectMapper;
    private final RedisCompareLock redisLock;
    private final TurnSelector turnSelector;
    private final CompactionFallbackHandler fallbackHandler;
    private final SummaryContentValidator validator;
    private final ObservabilityRecorder recorder;
    private final ToolResultRenderer toolResultRenderer;

    public SummaryCompactor(SummarySnapshotRepository summaryRepository, MessageRepository messageRepository,
                            MemoryWriteQueue writeQueue, BudgetCalculator budgetCalculator,
                            TokenEstimator tokenEstimator, CompactConfig config, ObjectMapper objectMapper,
                            StringRedisTemplate redis, TurnSelector turnSelector,
                            CompactionFallbackHandler fallbackHandler, SummaryContentValidator validator,
                            ObservabilityRecorder recorder, ToolResultRenderer toolResultRenderer) {
        this.summaryRepository = summaryRepository;
        this.messageRepository = messageRepository;
        this.writeQueue = writeQueue;
        this.budgetCalculator = budgetCalculator;
        this.tokenEstimator = tokenEstimator;
        this.config = config;
        this.objectMapper = objectMapper;
        this.redisLock = new RedisCompareLock(redis);
        this.turnSelector = turnSelector;
        this.fallbackHandler = fallbackHandler;
        this.validator = validator;
        this.recorder = recorder;
        this.toolResultRenderer = toolResultRenderer;
    }

    /**
     * 是否有压缩任务正在执行（非阻塞，供读侧决定是否提交新任务）。
     *
     * @param sessionId 会话 ID
     * @return true 表示当前有压缩任务在跑
     */
    public boolean isCompacting(String sessionId) {
        return redisLock.isHeld(lockKey(sessionId));
    }

    /**
     * 执行一次压缩（在异步线程内调用）。
     *
     * @param sessionId 会话 ID
     */
    public void compact(String sessionId) {
        String lockKey = lockKey(sessionId);
        String token = UUID.randomUUID().toString();
        if (!redisLock.tryAcquire(lockKey, token, config.getSummaryLockTtlMillis())) {
            return;
        }
        AtomicBoolean lockLost = new AtomicBoolean(false);
        Thread renewer = startRenewal(lockKey, token, lockLost);
        Span span = startSpan(sessionId);
        try {
            doCompact(sessionId, span, lockLost);
        } catch (Exception e) {
            if (lockLost.get()) {
                log.error("summary lock lost, sessionId={}", sessionId, e);
                return;
            }
            log.error("summary compaction failed, sessionId={}", sessionId, e);
            SummarySnapshot latestUsable = summaryRepository.findLatestUsable(sessionId).orElse(null);
            long oldBoundary = latestUsable == null ? 0L : latestUsable.getSummarizedUpToSeq();
            persistFailed(sessionId, oldBoundary, CompactionFailureKindEnum.OTHER,
                    CompactionConstants.STAGE_SUMMARIZE, e.getMessage(), span, lockLost);
        } finally {
            renewer.interrupt();
            endSpan(span);
            redisLock.release(lockKey, token);
        }
    }

    private void doCompact(String sessionId, Span span, AtomicBoolean lockLost) {
        SummarySnapshot latestUsable = summaryRepository.findLatestUsable(sessionId).orElse(null);
        long oldBoundary = latestUsable != null ? latestUsable.getSummarizedUpToSeq() : 0L;
        SummaryContent existing = extractStructured(latestUsable);
        List<AgentMessage> tail = toolResultRenderer.render(
                SessionHistory.load(messageRepository, writeQueue, sessionId, oldBoundary), false);
        if (tail.size() < config.getMinMessagesToCompact()) {
            return;
        }

        // double-check 与读路径一致：只算摘要边界之后的原文
        int summaryTokens = estimateContentTokens(existing);
        int historyTokens = tokenEstimator.estimateMessages(tail);
        int fixedOverhead = MemoryBudgetContextHolder.fixedOverhead();
        BudgetResult budget = budgetCalculator.compute(config, fixedOverhead, summaryTokens, historyTokens);
        if (!budget.isTriggerCompaction()) {
            return;
        }

        List<AgentMessage> candidates = pickToCompact(tail, oldBoundary, config.getKeepRecentMessages());
        if (candidates.size() < config.getMinMessagesToCompact()) {
            return;
        }
        if (lockLost.get()) {
            log.error("summary lock lost before summarize, sessionId={}", sessionId);
            return;
        }
        List<AgentMessage> toCompact = turnSelector.select(candidates);
        ExtractOutcome extractOutcome = runExtract(sessionId, toCompact);
        StructuredExtraction extraction = extractOutcome.getExtraction();
        SummaryStatusEnum status = extractOutcome.getStatus();

        SummarizeResult summarizeResult;
        try {
            summarizeResult = fallbackHandler.summarizeWithFallback(sessionId, existing, extraction, toCompact);
        } catch (CompactionException e) {
            persistFailed(sessionId, oldBoundary, e.getKind(), CompactionConstants.STAGE_SUMMARIZE,
                    e.getMessage(), span, lockLost);
            return;
        }
        if (summarizeResult.isChunked() && status == SummaryStatusEnum.SUCCESS) {
            status = SummaryStatusEnum.PARTIAL;
        }
        if (!validator.isValid(summarizeResult.getContent())) {
            persistFailed(sessionId, oldBoundary, CompactionFailureKindEnum.VALIDATION,
                    CompactionConstants.STAGE_VALIDATE, "structured summary validation failed", span, lockLost);
            return;
        }
        long newBoundary = toCompact.get(toCompact.size() - 1).getSeq();
        SummarySnapshot saved = saveSnapshot(sessionId, summarizeResult.getContent(), toCompact, newBoundary, status,
                null, lockLost);
        if (saved == null) {
            return;
        }
        log.info("summary compacted, sessionId={}, version={}, status={}, summarizedUpToSeq={}, compactedCount={}",
                sessionId, saved.getVersion(), status.getCode(), newBoundary, toCompact.size());
    }

    /**
     * 按锁 TTL 的 {@link CompactionConstants#LOCK_RENEW_DIVISOR} 续期。续期失败表示锁已不属于本次任务。
     */
    private Thread startRenewal(String lockKey, String token, AtomicBoolean lockLost) {
        long interval = Math.max(1L, config.getSummaryLockTtlMillis() / CompactionConstants.LOCK_RENEW_DIVISOR);
        return Thread.ofVirtual().name("summary-lock-", 0).start(() -> renewLoop(lockKey, token, interval, lockLost));
    }

    private void renewLoop(String lockKey, String token, long intervalMillis, AtomicBoolean lockLost) {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(intervalMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!redisLock.renew(lockKey, token, config.getSummaryLockTtlMillis())) {
                lockLost.set(true);
                log.error("summary lock renew failed, key={}", lockKey);
                return;
            }
        }
    }

    /** 产出候选段：seq > oldBoundary 且排除最近 keepRecent 条原文（再由 TurnSelector 打分截断） */
    private List<AgentMessage> pickToCompact(List<AgentMessage> merged, long oldBoundary, int keepRecent) {
        int size = merged.size();
        int recentBoundaryIndex = Math.max(0, size - keepRecent);
        List<AgentMessage> toCompact = new ArrayList<>();
        for (int i = 0; i < recentBoundaryIndex; i++) {
            AgentMessage message = merged.get(i);
            if (message.getSeq() <= oldBoundary) {
                continue;
            }
            if (ToolCompactionStrategyEnum.RETAIN.getCode().equals(message.getCompactionClass())) {
                continue;
            }
            toCompact.add(message);
        }
        return toCompact;
    }

    /**
     * 第一层提取：失败标 DEGRADED 并继续，不中断管道。
     */
    private ExtractOutcome runExtract(String sessionId, List<AgentMessage> toCompact) {
        ExtractOutcome outcome = new ExtractOutcome();
        outcome.setStatus(SummaryStatusEnum.SUCCESS);
        if (!config.isExtractionEnabled()) {
            return outcome;
        }
        try {
            outcome.setExtraction(fallbackHandler.extractWithRetry(sessionId, toCompact));
        } catch (CompactionException e) {
            outcome.setStatus(SummaryStatusEnum.DEGRADED);
            log.warn("extraction degraded, continue summarize, sessionId={}, kind={}",
                    sessionId, e.getKind().getCode());
        }
        return outcome;
    }

    private void persistFailed(String sessionId, long oldBoundary, CompactionFailureKindEnum kind, String stage,
                               String errorMessage, Span span, AtomicBoolean lockLost) {
        if (lockLost.get()) {
            log.error("summary lock lost, skip failure snapshot, sessionId={}", sessionId);
            return;
        }
        saveFailedSnapshot(sessionId, oldBoundary, errorMessage);
        recordFailed(span, sessionId, stage, kind, errorMessage);
        log.error("summary compaction failed, sessionId={}, stage={}, kind={}",
                sessionId, stage, kind.getCode());
    }

    private void saveFailedSnapshot(String sessionId, long oldBoundary, String errorMessage) {
        SummaryContent content = new SummaryContent();
        content.setSchemaVersion(2);
        SummarySnapshot snapshot = new SummarySnapshot();
        snapshot.setSummaryId(UUID.randomUUID().toString());
        snapshot.setSessionId(sessionId);
        snapshot.setSections(writeJson(content));
        snapshot.setSummarizedUpToSeq(oldBoundary);
        snapshot.setStatus(SummaryStatusEnum.FAILED.getCode());
        snapshot.setErrorMessage(errorMessage);
        snapshot.setUpdatedAt(Instant.now());
        summaryRepository.save(snapshot);
    }

    /**
     * @return 已保存的快照。锁已经丢失时返回 null，不写入
     */
    private SummarySnapshot saveSnapshot(String sessionId, SummaryContent content, List<AgentMessage> toCompact,
                                         long newBoundary, SummaryStatusEnum status, String errorMessage,
                                         AtomicBoolean lockLost) {
        if (lockLost.get()) {
            log.error("summary lock lost, skip snapshot, sessionId={}", sessionId);
            return null;
        }
        content.setSchemaVersion(2);
        fillProvenance(content, toCompact, newBoundary, status);

        SummarySnapshot snapshot = new SummarySnapshot();
        snapshot.setSummaryId(UUID.randomUUID().toString());
        snapshot.setSessionId(sessionId);
        snapshot.setSections(writeJson(content));
        snapshot.setSummarizedUpToSeq(newBoundary);
        snapshot.setStatus(status.getCode());
        snapshot.setErrorMessage(errorMessage);
        snapshot.setUpdatedAt(Instant.now());
        summaryRepository.save(snapshot);
        return snapshot;
    }

    private void fillProvenance(SummaryContent content, List<AgentMessage> toCompact, long newBoundary,
                                SummaryStatusEnum status) {
        SummaryContent.Provenance provenance = new SummaryContent.Provenance();
        provenance.setSummarizedUpToSeq(newBoundary);
        provenance.setCompactedAt(Instant.now());
        provenance.setStatus(status.getCode());
        List<String> coveredIds = new ArrayList<>(toCompact.size());
        for (AgentMessage message : toCompact) {
            coveredIds.add(message.getMessageId());
        }
        provenance.setCoveredMessageIds(coveredIds);
        content.setProvenance(provenance);
    }

    /**
     * 解析快照为结构化摘要。FAILED 状态视为无可用摘要。
     *
     * @param snapshot 最新快照
     * @return 结构化内容，解析失败或 FAILED 时返回 null
     */
    private SummaryContent extractStructured(SummarySnapshot snapshot) {
        if (snapshot == null || snapshot.getSections() == null) {
            return null;
        }
        if (SummaryStatusEnum.FAILED.getCode().equals(snapshot.getStatus())) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshot.getSections(), SummaryContent.class);
        } catch (Exception e) {
            log.warn("parse summary sections failed, fallback to null, sessionId={}",
                    snapshot.getSessionId(), e);
            return null;
        }
    }

    private int estimateContentTokens(SummaryContent content) {
        if (content == null) {
            return 0;
        }
        return tokenEstimator.estimate(new CompactionMessage(content).getText());
    }

    private Span startSpan(String sessionId) {
        if (recorder == null) {
            return null;
        }
        return recorder.startSpan(SpanTypeEnum.COMPACTION, null, sessionId);
    }

    private void endSpan(Span span) {
        if (recorder == null || span == null) {
            return;
        }
        recorder.endSpan(span.getSpanId());
    }

    private void recordFailed(Span span, String sessionId, String stage,
                              CompactionFailureKindEnum kind, String errorMessage) {
        if (recorder == null || span == null) {
            return;
        }
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put(CompactionConstants.PAYLOAD_SESSION_ID, sessionId);
        payload.put(CompactionConstants.PAYLOAD_STAGE, stage);
        payload.put(CompactionConstants.PAYLOAD_KIND, kind.getCode());
        payload.put(CompactionConstants.PAYLOAD_MESSAGE, errorMessage);
        recorder.recordEvent(span.getSpanId(), EventTypeEnum.COMPACTION_FAILED, writePayload(payload));
    }

    private String writePayload(Map<String, String> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return payload.toString();
        }
    }

    private String writeJson(SummaryContent content) {
        try {
            return objectMapper.writeValueAsString(content);
        } catch (Exception e) {
            throw new IllegalStateException("serialize SummaryContent failed", e);
        }
    }

    private String lockKey(String sessionId) {
        return LOCK_PREFIX + sessionId;
    }

    /** 第一层提取结果：提取物 + 是否已降级 */
    private static class ExtractOutcome {

        private StructuredExtraction extraction;
        private SummaryStatusEnum status;

        public StructuredExtraction getExtraction() {
            return extraction;
        }

        public void setExtraction(StructuredExtraction extraction) {
            this.extraction = extraction;
        }

        public SummaryStatusEnum getStatus() {
            return status;
        }

        public void setStatus(SummaryStatusEnum status) {
            this.status = status;
        }
    }
}
