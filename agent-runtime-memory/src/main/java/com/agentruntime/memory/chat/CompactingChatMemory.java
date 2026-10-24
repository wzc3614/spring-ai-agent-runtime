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
package com.agentruntime.memory.chat;

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.domain.SummaryContent;
import com.agentruntime.api.domain.SummarySnapshot;
import com.agentruntime.api.enums.SummaryStatusEnum;
import com.agentruntime.api.enums.ToolCompactionStrategyEnum;
import com.agentruntime.api.memory.CompactConfig;
import com.agentruntime.api.memory.MessageWritePolicy;
import com.agentruntime.api.memory.SessionSeqProvider;
import com.agentruntime.api.memory.ToolCompactionPolicyRegistry;
import com.agentruntime.api.repository.MessageRepository;
import com.agentruntime.api.repository.SummarySnapshotRepository;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.core.springai.MemoryBudgetContextHolder;
import com.agentruntime.memory.budget.BudgetCalculator;
import com.agentruntime.memory.budget.BudgetResult;
import com.agentruntime.memory.compaction.SummaryCompactor;
import com.agentruntime.memory.compaction.SummaryExecutor;
import com.agentruntime.memory.compaction.ToolMessagePreparer;
import com.agentruntime.memory.compaction.ToolPayloadCodec;
import com.agentruntime.memory.compaction.ToolResultRenderer;
import com.agentruntime.memory.history.SessionHistory;
import com.agentruntime.memory.write.MemoryWriteQueue;
import com.agentruntime.memory.write.WriteTask;
import com.agentruntime.memory.write.WriteTaskConverter;
import com.agentruntime.memory.write.WriteTaskIdGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.util.CollectionUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 压缩式对话记忆，实现 Spring AI {@link ChatMemory}。
 *
 * <p>写路径：add() 把每条消息编码为存储信封，经 Redis 分配 seq 后入异步队列（读写分离）。
 * 读路径：get() 合并 DB 历史 + Redis 队列未消费任务，若存在可用摘要快照则渲染为
 * {@link CompactionMessage} 注入并仅取摘要边界之后的历史；按预算保留最近消息后解码返回。
 * 当预算超阈值时，异步触发 {@link SummaryCompactor} 压缩（fire-and-forget，不阻塞主流程）。
 * 注入模型前按工具策略替换工具结果视图：当前轮原文，之后查询类过期、搜索类摘要、操作类保留。
 */
public class CompactingChatMemory implements ChatMemory {

    private static final Logger log = LoggerFactory.getLogger(CompactingChatMemory.class);

    private final ChatModel chatModel;
    private final CompactConfig config;
    private final ToolCompactionPolicyRegistry policyRegistry;
    private final MessageWritePolicy writePolicy;
    private final SessionSeqProvider seqProvider;
    private final MemoryWriteQueue writeQueue;
    private final MessageRepository messageRepository;
    private final TokenEstimator tokenEstimator;
    private final MessageConverter messageConverter;
    private final SummarySnapshotRepository summaryRepository;
    private final SummaryCompactor summaryCompactor;
    private final BudgetCalculator budgetCalculator;
    private final SummaryExecutor summaryExecutor;
    private final ObjectMapper objectMapper;
    private final ToolResultRenderer toolResultRenderer;
    private final ToolMessagePreparer toolMessagePreparer;

    public CompactingChatMemory(ChatModel chatModel, CompactConfig config, ToolCompactionPolicyRegistry policyRegistry,
                                 MessageWritePolicy writePolicy, SessionSeqProvider seqProvider,
                                 MemoryWriteQueue writeQueue, MessageRepository messageRepository,
                                 TokenEstimator tokenEstimator, MessageConverter messageConverter,
                                 SummarySnapshotRepository summaryRepository, SummaryCompactor summaryCompactor,
                                 BudgetCalculator budgetCalculator, SummaryExecutor summaryExecutor,
                                 ObjectMapper objectMapper, ToolResultRenderer toolResultRenderer) {
        this.chatModel = chatModel;
        this.config = config;
        this.policyRegistry = policyRegistry;
        this.writePolicy = writePolicy;
        this.seqProvider = seqProvider;
        this.writeQueue = writeQueue;
        this.messageRepository = messageRepository;
        this.tokenEstimator = tokenEstimator;
        this.messageConverter = messageConverter;
        this.summaryRepository = summaryRepository;
        this.summaryCompactor = summaryCompactor;
        this.budgetCalculator = budgetCalculator;
        this.summaryExecutor = summaryExecutor;
        this.objectMapper = objectMapper;
        this.toolResultRenderer = toolResultRenderer;
        this.toolMessagePreparer = new ToolMessagePreparer(messageConverter, policyRegistry,
                new ToolPayloadCodec(objectMapper));
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            return;
        }
        // Spring AI ChatMemory 接口只传一个 id；编排器把 sessionId 作为 CONVERSATION_ID 传入，
        // 此处 memoryKey 即 sessionId（记忆按 session 共享）。
        String sessionId = conversationId;
        String turnId = UUID.randomUUID().toString();
        for (Message message : messages) {
            String roleCode = message.getMessageType().getValue();
            long seq = seqProvider.nextSeq(sessionId);
            String messageId = UUID.randomUUID().toString();
            WriteTask task = newTask(sessionId, turnId, messageId, seq, roleCode);
            toolMessagePreparer.apply(message, task);
            if (!writePolicy.shouldWrite(sessionId, turnId, WriteTaskConverter.toMessage(task))) {
                continue;
            }
            writeQueue.enqueue(task);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        // 记忆按 session 共享：传入的 conversationId 实为 sessionId
        String sessionId = conversationId;
        SummarySnapshot snapshot = summaryRepository.findLatestUsable(sessionId).orElse(null);
        SummaryContent content = extractStructured(snapshot);
        boolean hasSummary = isUsable(content);
        long boundary = hasSummary ? snapshot.getSummarizedUpToSeq() : 0L;
        List<AgentMessage> historyMessages = toolResultRenderer.render(
                SessionHistory.loadForPrompt(messageRepository, writeQueue, sessionId, boundary), true);

        // 预算判定 + 异步触发压缩（fire-and-forget，不阻塞）
        int summaryTokens = hasSummary ? tokenEstimator.estimate(new CompactionMessage(content).getText()) : 0;
        int historyTokens = tokenEstimator.estimateMessages(summarizable(historyMessages));
        int fixedOverhead = MemoryBudgetContextHolder.fixedOverhead();
        BudgetResult budget = budgetCalculator.compute(config, fixedOverhead, summaryTokens, historyTokens);
        if (budget.isTriggerCompaction() && !summaryCompactor.isCompacting(sessionId)) {
            summaryExecutor.submit(() -> summaryCompactor.compact(sessionId));
        }

        // 兜底截断：超预算时从最早历史截断（保留 summary 不丢）
        List<AgentMessage> kept = keepByBudget(historyMessages, summaryTokens);

        // 组装结果：摘要在前 + 历史解码
        List<Message> result = new ArrayList<>(kept.size() + 1);
        if (hasSummary) {
            result.add(new CompactionMessage(content));
        }
        for (AgentMessage am : kept) {
            result.add(messageConverter.decode(am));
        }
        return result;
    }

    @Override
    public void clear(String conversationId) {
        // 记忆按 session 共享：传入的 conversationId 实为 sessionId
        writeQueue.discardSession(conversationId, () -> messageRepository.deleteBySession(conversationId));
    }

    /**
     * 会话摘要只能消化非 RETAIN 消息。操作类原文单独回捞，不拿它们的体积反复触发摘要。
     */
    private List<AgentMessage> summarizable(List<AgentMessage> messages) {
        List<AgentMessage> eligible = new ArrayList<>();
        for (AgentMessage message : messages) {
            if (!ToolCompactionStrategyEnum.RETAIN.getCode().equals(message.getCompactionClass())) {
                eligible.add(message);
            }
        }
        return eligible;
    }

    /**
     * 预算兜底：从最近向最远累加 token，超出历史预算则截断较早消息。历史预算需扣除固定开销与摘要占用。
     */
    private List<AgentMessage> keepByBudget(List<AgentMessage> messages, int summaryTokens) {
        int effectiveWindow = config.getContextWindow() - config.getReservedOutput() - config.getSafetyBuffer();
        int historyBudget = effectiveWindow - MemoryBudgetContextHolder.fixedOverhead() - summaryTokens;
        if (historyBudget <= 0 || messages.isEmpty()) {
            return messages;
        }
        int accumulated = 0;
        int cutFrom = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            int tokens = tokenEstimator.estimate(messages.get(i).getContent());
            if (accumulated + tokens > historyBudget) {
                cutFrom = i + 1;
                break;
            }
            accumulated += tokens;
        }
        if (cutFrom > 0) {
            log.warn("history over budget, truncated oldest, sessionId={}, kept={}",
                    messages.get(0).getSessionId(), messages.size() - cutFrom);
            return new ArrayList<>(messages.subList(cutFrom, messages.size()));
        }
        return messages;
    }

    /**
     * 解析快照为结构化摘要。FAILED 状态视为无可用摘要，读侧回退到全量原文。
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

    /** 结构化字段或一段式 summary 任一非空即视为可用 */
    private boolean isUsable(SummaryContent content) {
        if (content == null) {
            return false;
        }
        return !content.isStructuredEmpty()
                || (content.getSummary() != null && !content.getSummary().isBlank());
    }

    private WriteTask newTask(String sessionId, String turnId, String messageId, long seq, String roleCode) {
        WriteTask task = new WriteTask();
        task.setTaskId(WriteTaskIdGenerator.generate(roleCode, sessionId, turnId, seq));
        task.setSessionId(sessionId);
        task.setTurnId(turnId);
        task.setMessageId(messageId);
        task.setSeq(seq);
        task.setRoleCode(roleCode);
        task.setCompacted(false);
        task.setCreatedAtEpochMillis(Instant.now().toEpochMilli());
        return task;
    }

    public ChatModel getChatModel() {
        return chatModel;
    }

    public CompactConfig getConfig() {
        return config;
    }

    public ToolCompactionPolicyRegistry getPolicyRegistry() {
        return policyRegistry;
    }
}
