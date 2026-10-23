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
import com.agentruntime.api.memory.CompactConfig;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.api.repository.MessageRepository;
import com.agentruntime.api.repository.SummarySnapshotRepository;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.core.springai.MemoryBudgetContextHolder;
import com.agentruntime.memory.budget.BudgetCalculator;
import com.agentruntime.memory.budget.BudgetResult;
import com.agentruntime.memory.chat.CompactionMessage;
import com.agentruntime.memory.write.MemoryWriteQueue;
import com.agentruntime.memory.write.WriteTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 摘要压缩协调器：在异步线程内对一段对话历史做分层压缩，持久化为新版本 {@link SummarySnapshot}。
 * per-session Redis 锁保证分布式下同一会话同时只有一个压缩任务。
 *
 * <p>流程：加锁 → double-check 预算 → 划定候选段 → {@link TurnSelector} 打分截断 →
 * 可选 {@link StructuredExtractor}（失败标 DEGRADED 继续）→ {@link ChatHistorySummarizer}
 * （上下文不足分批合并；非法 JSON 重试后仍失败则 FAILED）→ 第四层校验 → 保存快照。
 * FAILED 不覆盖旧摘要、不推进边界，只记 {@link EventTypeEnum#COMPACTION_FAILED}。
 */
public class SummaryCompactor {

    private static final Logger log = LoggerFactory.getLogger(SummaryCompactor.class);

    private static final String LOCK_PREFIX = "agentruntime:summarylock:";

    /** 释放锁的 Lua：仅当 value 等于 token 才 del */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final SummarySnapshotRepository summaryRepository;
    private final MessageRepository messageRepository;
    private final MemoryWriteQueue writeQueue;
    private final BudgetCalculator budgetCalculator;
    private final TokenEstimator tokenEstimator;
    private final CompactConfig config;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;
    private final TurnSelector turnSelector;
    private final CompactionFallbackHandler fallbackHandler;
    private final SummaryContentValidator validator;
    private final ObservabilityRecorder recorder;

    public SummaryCompactor(SummarySnapshotRepository summaryRepository, MessageRepository messageRepository,
                            MemoryWriteQueue writeQueue, BudgetCalculator budgetCalculator,
                            TokenEstimator tokenEstimator, CompactConfig config, ObjectMapper objectMapper,
                            StringRedisTemplate redis, TurnSelector turnSelector,
                            CompactionFallbackHandler fallbackHandler, SummaryContentValidator validator,
                            ObservabilityRecorder recorder) {
        this.summaryRepository = summaryRepository;
        this.messageRepository = messageRepository;
        this.writeQueue = writeQueue;
        this.budgetCalculator = budgetCalculator;
        this.tokenEstimator = tokenEstimator;
        this.config = config;
        this.objectMapper = objectMapper;
        this.redis = redis;
        this.turnSelector = turnSelector;
        this.fallbackHandler = fallbackHandler;
        this.validator = validator;
        this.recorder = recorder;
    }

    /**
     * 是否有压缩任务正在执行（非阻塞，供读侧决定是否提交新任务）。
     *
     * @param sessionId 会话 ID
     * @return true 表示当前有压缩任务在跑
     */
    public boolean isCompacting(String sessionId) {
        Boolean exists = redis.hasKey(lockKey(sessionId));
        return Boolean.TRUE.equals(exists);
    }

    /**
     * 执行一次压缩（在异步线程内调用）。
     *
     * @param sessionId 会话 ID
     */
    public void compact(String sessionId) {
        String lockKey = lockKey(sessionId);
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token,
                Duration.ofMillis(config.getSummaryLockTtlMillis()));
        if (Boolean.FALSE.equals(acquired)) {
            return;
        }
        Span span = startSpan(sessionId);
        try {
            doCompact(sessionId, span);
        } catch (Exception e) {
            log.error("summary compaction failed, sessionId={}", sessionId, e);
            SummarySnapshot latestAny = summaryRepository.findLatest(sessionId).orElse(null);
            SummarySnapshot latestUsable = summaryRepository.findLatestUsable(sessionId).orElse(null);
            long oldBoundary = latestUsable == null ? 0L : latestUsable.getSummarizedUpToSeq();
            persistFailed(sessionId, latestAny, oldBoundary, CompactionFailureKindEnum.OTHER,
                    CompactionConstants.STAGE_SUMMARIZE, e.getMessage(), span);
        } finally {
            endSpan(span);
            unlock(lockKey, token);
        }
    }

    private void doCompact(String sessionId, Span span) {
        List<AgentMessage> merged = loadAndMerge(sessionId);
        if (merged.size() < config.getMinMessagesToCompact()) {
            return;
        }
        SummarySnapshot latestAny = summaryRepository.findLatest(sessionId).orElse(null);
        SummarySnapshot latestUsable = summaryRepository.findLatestUsable(sessionId).orElse(null);
        long oldBoundary = latestUsable != null ? latestUsable.getSummarizedUpToSeq() : 0L;
        SummaryContent existing = extractStructured(latestUsable);

        // double-check：重新算预算，避免触发后又被别处压缩导致重复
        int summaryTokens = estimateContentTokens(existing);
        int historyTokens = tokenEstimator.estimateMessages(merged);
        int fixedOverhead = MemoryBudgetContextHolder.fixedOverhead();
        BudgetResult budget = budgetCalculator.compute(config, fixedOverhead, summaryTokens, historyTokens);
        if (!budget.isTriggerCompaction()) {
            return;
        }

        List<AgentMessage> candidates = pickToCompact(merged, oldBoundary, config.getKeepRecentMessages());
        if (candidates.size() < config.getMinMessagesToCompact()) {
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
            persistFailed(sessionId, latestAny, oldBoundary, e.getKind(), CompactionConstants.STAGE_SUMMARIZE,
                    e.getMessage(), span);
            return;
        }
        if (summarizeResult.isChunked() && status == SummaryStatusEnum.SUCCESS) {
            status = SummaryStatusEnum.PARTIAL;
        }
        if (!validator.isValid(summarizeResult.getContent())) {
            persistFailed(sessionId, latestAny, oldBoundary, CompactionFailureKindEnum.VALIDATION,
                    CompactionConstants.STAGE_VALIDATE, "structured summary validation failed", span);
            return;
        }
        long newBoundary = toCompact.get(toCompact.size() - 1).getSeq();
        saveSnapshot(sessionId, latestAny, summarizeResult.getContent(), toCompact, newBoundary, status, null);
        log.info("summary compacted, sessionId={}, version={}, status={}, summarizedUpToSeq={}, compactedCount={}",
                sessionId, latestAny == null ? 1 : latestAny.getVersion() + 1,
                status.getCode(), newBoundary, toCompact.size());
    }

    /** 合并 DB 历史 + Redis 队列未消费任务，按 seq 升序去重 */
    private List<AgentMessage> loadAndMerge(String sessionId) {
        Map<Long, AgentMessage> bySeq = new LinkedHashMap<>();
        for (AgentMessage m : messageRepository.findBySession(sessionId)) {
            bySeq.put(m.getSeq(), m);
        }
        for (WriteTask task : writeQueue.peekAll(sessionId)) {
            bySeq.putIfAbsent(task.getSeq(), toAgentMessage(task));
        }
        List<AgentMessage> merged = new ArrayList<>(bySeq.values());
        merged.sort(Comparator.comparingLong(AgentMessage::getSeq));
        return merged;
    }

    /** 产出候选段：seq > oldBoundary 且排除最近 keepRecent 条原文（再由 TurnSelector 打分截断） */
    private List<AgentMessage> pickToCompact(List<AgentMessage> merged, long oldBoundary, int keepRecent) {
        int size = merged.size();
        int recentBoundaryIndex = Math.max(0, size - keepRecent);
        List<AgentMessage> toCompact = new ArrayList<>();
        for (int i = 0; i < recentBoundaryIndex; i++) {
            AgentMessage m = merged.get(i);
            if (m.getSeq() > oldBoundary) {
                toCompact.add(m);
            }
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

    private void persistFailed(String sessionId, SummarySnapshot latestAny, long oldBoundary,
                               CompactionFailureKindEnum kind, String stage, String errorMessage, Span span) {
        saveFailedSnapshot(sessionId, latestAny, oldBoundary, errorMessage);
        recordFailed(span, sessionId, stage, kind, errorMessage);
        log.error("summary compaction failed, sessionId={}, stage={}, kind={}",
                sessionId, stage, kind.getCode());
    }

    private void saveFailedSnapshot(String sessionId, SummarySnapshot latestAny, long oldBoundary, String errorMessage) {
        SummaryContent content = new SummaryContent();
        content.setSchemaVersion(2);
        SummarySnapshot snapshot = new SummarySnapshot();
        snapshot.setSummaryId(UUID.randomUUID().toString());
        snapshot.setSessionId(sessionId);
        snapshot.setVersion(latestAny == null ? 1 : latestAny.getVersion() + 1);
        snapshot.setSections(writeJson(content));
        snapshot.setSummarizedUpToSeq(oldBoundary);
        snapshot.setStatus(SummaryStatusEnum.FAILED.getCode());
        snapshot.setErrorMessage(errorMessage);
        snapshot.setUpdatedAt(Instant.now());
        summaryRepository.save(snapshot);
    }

    private void saveSnapshot(String sessionId, SummarySnapshot latest, SummaryContent content,
                              List<AgentMessage> toCompact, long newBoundary, SummaryStatusEnum status,
                              String errorMessage) {
        content.setSchemaVersion(2);
        fillProvenance(content, toCompact, newBoundary, status);

        SummarySnapshot snapshot = new SummarySnapshot();
        snapshot.setSummaryId(UUID.randomUUID().toString());
        snapshot.setSessionId(sessionId);
        snapshot.setVersion(latest == null ? 1 : latest.getVersion() + 1);
        snapshot.setSections(writeJson(content));
        snapshot.setSummarizedUpToSeq(newBoundary);
        snapshot.setStatus(status.getCode());
        snapshot.setErrorMessage(errorMessage);
        snapshot.setUpdatedAt(Instant.now());
        summaryRepository.save(snapshot);
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

    private AgentMessage toAgentMessage(WriteTask task) {
        AgentMessage message = new AgentMessage();
        message.setMessageId(task.getMessageId());
        message.setSessionId(task.getSessionId());
        message.setSeq(task.getSeq());
        message.setRole(com.agentruntime.api.enums.MessageRoleEnum.of(task.getRoleCode()));
        message.setContent(task.getContent());
        message.setCompacted(task.isCompacted());
        message.setEvidenceId(task.getEvidenceId());
        message.setCreatedAt(Instant.ofEpochMilli(task.getCreatedAtEpochMillis()));
        return message;
    }

    private String lockKey(String sessionId) {
        return LOCK_PREFIX + sessionId;
    }

    private void unlock(String lockKey, String token) {
        redis.execute(UNLOCK_SCRIPT, java.util.Collections.singletonList(lockKey), token);
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
