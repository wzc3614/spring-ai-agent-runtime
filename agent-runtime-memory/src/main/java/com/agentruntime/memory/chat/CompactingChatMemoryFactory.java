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

import com.agentruntime.api.memory.CompactConfig;
import com.agentruntime.api.memory.MessageWritePolicy;
import com.agentruntime.api.memory.SessionSeqProvider;
import com.agentruntime.api.memory.ToolCompactionPolicyRegistry;
import com.agentruntime.api.repository.MessageRepository;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.memory.write.MemoryWriteQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;

/**
 * CompactingChatMemory 工厂（Spring bean，持有全部基础设施）。
 * 使用者注入此工厂后调用 {@link #builder(ChatModel)} 获得流式 builder。
 */
public class CompactingChatMemoryFactory {

    private final MessageWritePolicy writePolicy;
    private final SessionSeqProvider seqProvider;
    private final MemoryWriteQueue writeQueue;
    private final MessageRepository messageRepository;
    private final TokenEstimator tokenEstimator;
    private final ObjectMapper objectMapper;

    public CompactingChatMemoryFactory(MessageWritePolicy writePolicy, SessionSeqProvider seqProvider,
                                        MemoryWriteQueue writeQueue, MessageRepository messageRepository,
                                        TokenEstimator tokenEstimator, ObjectMapper objectMapper) {
        this.writePolicy = writePolicy;
        this.seqProvider = seqProvider;
        this.writeQueue = writeQueue;
        this.messageRepository = messageRepository;
        this.tokenEstimator = tokenEstimator;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建 builder。
     *
     * @param chatModel 压缩用模型（M3 LLM 摘要使用，M1 暂存）
     * @return builder
     */
    public CompactingChatMemoryBuilder builder(ChatModel chatModel) {
        return new CompactingChatMemoryBuilder(this, chatModel);
    }

    /**
     * 实际构建（由 builder 调用）。
     *
     * @param chatModel     模型
     * @param config        配置
     * @param policyRegistry 策略注册表
     * @return CompactingChatMemory
     */
    public CompactingChatMemory create(ChatModel chatModel, CompactConfig config, ToolCompactionPolicyRegistry policyRegistry) {
        MessageConverter converter = new MessageConverter(objectMapper);
        return new CompactingChatMemory(chatModel, config, policyRegistry, writePolicy, seqProvider,
                writeQueue, messageRepository, tokenEstimator, converter);
    }
}
