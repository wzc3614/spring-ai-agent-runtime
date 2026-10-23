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
package com.agentruntime.runtime;

import com.agentruntime.api.lifecycle.AgentLifecycleListener;
import com.agentruntime.api.spill.SpillConfig;
import com.agentruntime.core.tool.ToolObjectResolver;
import com.agentruntime.memory.chat.CompactingChatMemory;
import com.agentruntime.observability.ObservabilityToolCallback;
import com.agentruntime.observability.ObservingChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentRuntime 流式 builder。基础设施由 {@link AgentRuntimeFactory} 注入，
 * 用户设置 agentName / agentVersion / defaultSystem / defaultOptions / tools / memory / spill / listener。
 */
public class AgentRuntimeBuilder {

    private final AgentRuntimeFactory factory;
    private final ChatModel chatModel;

    private String agentName;
    private String agentVersion;
    private String defaultSystem;
    private ChatOptions defaultOptions;
    private Object[] tools;
    private CompactingChatMemory memory;
    private SpillConfig spillConfig;
    private AgentLifecycleListener listener;

    public AgentRuntimeBuilder(AgentRuntimeFactory factory, ChatModel chatModel) {
        this.factory = factory;
        this.chatModel = chatModel;
    }

    public AgentRuntimeBuilder agentName(String agentName) {
        this.agentName = agentName;
        return this;
    }

    public AgentRuntimeBuilder agentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
        return this;
    }

    public AgentRuntimeBuilder defaultSystem(String defaultSystem) {
        this.defaultSystem = defaultSystem;
        return this;
    }

    public AgentRuntimeBuilder defaultOptions(ChatOptions defaultOptions) {
        this.defaultOptions = defaultOptions;
        return this;
    }

    public AgentRuntimeBuilder tools(Object... tools) {
        this.tools = tools;
        return this;
    }

    public AgentRuntimeBuilder memory(CompactingChatMemory memory) {
        this.memory = memory;
        return this;
    }

    public AgentRuntimeBuilder spill(SpillConfig spillConfig) {
        this.spillConfig = spillConfig;
        return this;
    }

    public AgentRuntimeBuilder listener(AgentLifecycleListener listener) {
        this.listener = listener;
        return this;
    }

    /**
     * 构建 AgentRuntime。
     *
     * @return AgentRuntime
     */
    public AgentRuntime build() {
        // 包一层观测式 ChatModel：每次 LLM 调用建 LlmCallSpan + 记 usage
        ChatModel observedChatModel = new ObservingChatModel(chatModel, factory.getRecorder(), factory.getObjectMapper());
        ChatClient.Builder builder = ChatClient.builder(observedChatModel);
        List<ToolCallback> resolvedTools = java.util.Collections.emptyList();
        if (defaultSystem != null) {
            builder.defaultSystem(defaultSystem);
        }
        if (defaultOptions != null) {
            builder.defaultOptions(defaultOptions);
        }
        if (tools != null && tools.length > 0) {
            // 解析 + 包装：raw -> GovernedToolCallback(重试/分类) -> ObservabilityToolCallback(span/事件)
            // 观测在最外层，一次逻辑工具调用对应一个 TOOL_CALL span，重试作为 span 内事件
            ToolCallback[] callbacks = new ToolObjectResolver().resolve(tools);
            List<ToolCallback> wrapped = new ArrayList<>(callbacks.length);
            for (ToolCallback callback : callbacks) {
                ToolCallback governed = new com.agentruntime.core.tool.GovernedToolCallback(callback,
                        factory.getToolMetaRegistry(), factory.getToolFailureClassifier(),
                        factory.getGovernanceDefaults());
                wrapped.add(new ObservabilityToolCallback(governed, factory.getRecorder(), factory.getObjectMapper()));
            }
            resolvedTools = wrapped;
        }
        if (memory != null) {
            // GA: MessageChatMemoryAdvisor 构造器改 private，须用 builder
            builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build());
        }
        ChatClient chatClient = builder.build();
        return new AgentRuntime(chatClient, agentName, agentVersion, memory, spillConfig, listener,
                factory.getSessionRepository(), factory.getRecorder(), resolvedTools,
                defaultSystem, factory.getTokenEstimator());
    }
}
