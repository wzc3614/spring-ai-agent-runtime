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
import com.agentruntime.api.memory.CompactConfig;
import com.agentruntime.api.memory.MessageWritePolicy;
import com.agentruntime.api.memory.SessionSeqProvider;
import com.agentruntime.api.memory.ToolCompactionPolicyRegistry;
import com.agentruntime.api.repository.MessageRepository;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.memory.write.MemoryWriteQueue;
import com.agentruntime.memory.write.WriteTask;
import com.agentruntime.memory.write.WriteTaskIdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.util.CollectionUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 压缩式对话记忆，实现 Spring AI {@link ChatMemory}。
 *
 * <p>写路径：add() 把每条消息编码为存储信封，经 Redis 分配 seq 后入异步队列（读写分离）。
 * 读路径：get() 合并 DB 历史 + Redis 队列未消费任务，按预算保留最近消息后解码返回。
 * M1 暂不接微压缩/LLM 摘要（超预算时保留最近 N 条 + warn）。
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

    public CompactingChatMemory(ChatModel chatModel, CompactConfig config, ToolCompactionPolicyRegistry policyRegistry,
                                 MessageWritePolicy writePolicy, SessionSeqProvider seqProvider,
                                 MemoryWriteQueue writeQueue, MessageRepository messageRepository,
                                 TokenEstimator tokenEstimator, MessageConverter messageConverter) {
        this.chatModel = chatModel;
        this.config = config;
        this.policyRegistry = policyRegistry;
        this.writePolicy = writePolicy;
        this.seqProvider = seqProvider;
        this.writeQueue = writeQueue;
        this.messageRepository = messageRepository;
        this.tokenEstimator = tokenEstimator;
        this.messageConverter = messageConverter;
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
            WriteTask task = newTask(sessionId, turnId, messageId, seq, roleCode,
                    messageConverter.encode(message));
            if (!writePolicy.shouldWrite(sessionId, turnId, toAgentMessage(task))) {
                continue;
            }
            writeQueue.enqueue(task);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        // 记忆按 session 共享：传入的 conversationId 实为 sessionId
        List<AgentMessage> merged = loadAndMerge(conversationId);
        List<AgentMessage> kept = keepByBudget(merged);
        List<Message> result = new ArrayList<>(kept.size());
        for (AgentMessage am : kept) {
            result.add(messageConverter.decode(am));
        }
        return result;
    }

    @Override
    public void clear(String conversationId) {
        // 记忆按 session 共享：传入的 conversationId 实为 sessionId
        writeQueue.drain(conversationId);
        writeQueue.removeFromPending(conversationId);
        messageRepository.deleteBySession(conversationId);
    }

    private List<AgentMessage> loadAndMerge(String sessionId) {
        List<AgentMessage> merged = new ArrayList<>(messageRepository.findBySession(sessionId));
        for (WriteTask task : writeQueue.peekAll(sessionId)) {
            merged.add(toAgentMessage(task));
        }
        merged.sort(Comparator.comparingLong(AgentMessage::getSeq));
        return merged;
    }

    /**
     * 预算兜底：从最近向最远累加 token，超出有效窗口则截断较早消息。M1 临时方案。
     */
    private List<AgentMessage> keepByBudget(List<AgentMessage> messages) {
        int effectiveWindow = config.getContextWindow() - config.getReservedOutput() - config.getSafetyBuffer();
        if (effectiveWindow <= 0 || messages.isEmpty()) {
            return messages;
        }
        int accumulated = 0;
        int cutFrom = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            int tokens = tokenEstimator.estimate(messages.get(i).getContent());
            if (accumulated + tokens > effectiveWindow) {
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

    private WriteTask newTask(String sessionId, String turnId, String messageId,
                              long seq, String roleCode, String content) {
        WriteTask task = new WriteTask();
        task.setTaskId(WriteTaskIdGenerator.generate(roleCode, sessionId, turnId, seq));
        task.setSessionId(sessionId);
        task.setTurnId(turnId);
        task.setMessageId(messageId);
        task.setSeq(seq);
        task.setRoleCode(roleCode);
        task.setContent(content);
        task.setCompacted(false);
        task.setCreatedAtEpochMillis(Instant.now().toEpochMilli());
        return task;
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
