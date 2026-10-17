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
import com.agentruntime.api.memory.ToolCompactionPolicyRegistry;
import org.springframework.ai.chat.model.ChatModel;

/**
 * CompactingChatMemory 的流式 builder。基础设施由 {@link CompactingChatMemoryFactory} 注入，
 * 用户只需设置 chatModel / config / policyRegistry。
 */
public class CompactingChatMemoryBuilder {

    private final CompactingChatMemoryFactory factory;

    private final ChatModel chatModel;

    private CompactConfig config;

    private ToolCompactionPolicyRegistry policyRegistry;

    public CompactingChatMemoryBuilder(CompactingChatMemoryFactory factory, ChatModel chatModel) {
        this.factory = factory;
        this.chatModel = chatModel;
    }

    /**
     * 设置压缩配置。
     *
     * @param config 配置
     * @return this
     */
    public CompactingChatMemoryBuilder config(CompactConfig config) {
        this.config = config;
        return this;
    }

    /**
     * 设置工具压缩策略注册表。
     *
     * @param policyRegistry 注册表
     * @return this
     */
    public CompactingChatMemoryBuilder policyRegistry(ToolCompactionPolicyRegistry policyRegistry) {
        this.policyRegistry = policyRegistry;
        return this;
    }

    /**
     * 构建。
     *
     * @return CompactingChatMemory
     */
    public CompactingChatMemory build() {
        CompactConfig effectiveConfig = config != null ? config : CompactConfig.ofModel(null);
        ToolCompactionPolicyRegistry effectiveRegistry = policyRegistry != null
                ? policyRegistry
                : ToolCompactionPolicyRegistry.withDefaults();
        return factory.create(chatModel, effectiveConfig, effectiveRegistry);
    }
}
