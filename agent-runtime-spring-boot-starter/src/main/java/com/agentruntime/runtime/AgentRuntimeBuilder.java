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
import com.agentruntime.capability.skill.trace.SkillToolTraceCallback;
import com.agentruntime.core.budget.BoundedToolCallingChatModel;
import com.agentruntime.core.tool.ToolObjectResolver;
import com.agentruntime.runtime.hook.TurnHook;
import com.agentruntime.memory.chat.CompactingChatMemory;
import com.agentruntime.observability.ObservabilityToolCallback;
import com.agentruntime.observability.ObservingChatModel;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * AgentRuntime 流式 builder。基础设施由 {@link AgentRuntimeFactory} 注入，
 * 用户设置 agentName / agentVersion / defaultSystem / defaultOptions / tools / memory / listener / turnHooks。
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
    private List<TurnHook> turnHooks = List.of();
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

    public AgentRuntimeBuilder listener(AgentLifecycleListener listener) {
        this.listener = listener;
        return this;
    }

    /**
     * 注册本轮钩子。按传入顺序执行，先打断的钩子生效。
     *
     * @param hooks 钩子
     * @return builder
     */
    public AgentRuntimeBuilder turnHooks(TurnHook... hooks) {
        if (hooks == null || hooks.length == 0) {
            this.turnHooks = List.of();
            return this;
        }
        this.turnHooks = List.copyOf(Arrays.asList(hooks));
        return this;
    }

    /**
     * 构建 AgentRuntime。
     *
     * @return AgentRuntime
     */
    public AgentRuntime build() {
        // 观测在内层：循环的每一步各建一个 MODEL_CALL span。
        // 预算在外层：关闭下游内部工具循环，按步数和累计 token 决定是否继续。
        ChatModel observedChatModel = new ObservingChatModel(chatModel, factory.getRecorder(),
                factory.getObjectMapper(), true);
        ChatModel boundedChatModel = new BoundedToolCallingChatModel(observedChatModel,
                factory.getToolCallingManager(), factory.getTurnBudget(), factory.getRecorder(),
                factory.getTokenEstimator());
        ChatClient.Builder builder = ChatClient.builder(boundedChatModel);
        List<ToolCallback> resolvedTools = Collections.emptyList();
        if (defaultSystem != null) {
            builder.defaultSystem(defaultSystem);
        }
        if (defaultOptions != null) {
            builder.defaultOptions(defaultOptions);
        }
        if (tools != null && tools.length > 0) {
            // 解析 + 包装：raw -> GovernedToolCallback(重试/分类) -> SkillToolTraceCallback -> ObservabilityToolCallback
            // 观测在最外层，一次逻辑工具调用对应一个 TOOL_CALL span，重试作为 span 内事件。
            // Skill 未启用时不插入轨迹装饰器。
            ToolCallback[] callbacks = new ToolObjectResolver().resolve(tools);
            List<ToolCallback> wrapped = new ArrayList<>(callbacks.length);
            boolean traceSkillCalls = factory.getSkillRuntime() != null;
            for (ToolCallback callback : callbacks) {
                ToolCallback governed = new com.agentruntime.core.tool.GovernedToolCallback(callback,
                        factory.getToolMetaRegistry(), factory.getToolFailureClassifier(),
                        factory.getGovernanceDefaults());
                ToolCallback traced = traceSkillCalls ? new SkillToolTraceCallback(governed) : governed;
                wrapped.add(new ObservabilityToolCallback(traced, factory.getRecorder(), factory.getObjectMapper()));
            }
            resolvedTools = wrapped;
        }
        if (memory != null) {
            // GA: MessageChatMemoryAdvisor 构造器改 private，须用 builder
            builder.defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build());
        }
        ChatClient chatClient = builder.build();
        AgentRuntimeDependencies dependencies = new AgentRuntimeDependencies();
        dependencies.setChatClient(chatClient);
        dependencies.setAgentName(agentName);
        dependencies.setAgentVersion(agentVersion);
        dependencies.setMemory(memory);
        dependencies.setListener(listener);
        dependencies.setSessionRepository(factory.getSessionRepository());
        dependencies.setRecorder(factory.getRecorder());
        dependencies.setTools(resolvedTools);
        dependencies.setDefaultSystem(defaultSystem);
        dependencies.setTokenEstimator(factory.getTokenEstimator());
        dependencies.setSkillRuntime(factory.getSkillRuntime());
        dependencies.setTurnHooks(turnHooks);
        dependencies.setTurnExecutor(factory.getTurnExecutor());
        dependencies.setObjectMapper(factory.getObjectMapper());
        dependencies.setTurnExecution(factory.getTurnExecutionEngine());
        return new AgentRuntime(dependencies);
    }
}
