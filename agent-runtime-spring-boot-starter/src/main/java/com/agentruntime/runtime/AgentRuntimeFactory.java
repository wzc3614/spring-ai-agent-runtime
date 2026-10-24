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

import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.api.repository.SessionRepository;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.api.spi.ToolFailureClassifier;
import com.agentruntime.capability.skill.SkillRuntime;
import com.agentruntime.core.tool.ToolGovernanceDefaults;
import com.agentruntime.core.tool.ToolMetaRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;

import java.util.concurrent.Executor;

/**
 * AgentRuntime 工厂（Spring bean，持有会话仓储等基础设施）。
 * 使用者注入此工厂后调用 {@link #builder(ChatModel)} 获得流式 builder。
 */
public class AgentRuntimeFactory {

    private final SessionRepository sessionRepository;

    private final ObservabilityRecorder recorder;

    private final ObjectMapper objectMapper;

    private final ToolMetaRegistry toolMetaRegistry;

    private final ToolFailureClassifier toolFailureClassifier;

    private final ToolGovernanceDefaults governanceDefaults;

    private final TokenEstimator tokenEstimator;

    /** 为空表示 Skill 未启用 */
    private final SkillRuntime skillRuntime;

    /** Skill 准备与轮次钩子并行时使用的执行器 */
    private final Executor turnExecutor;

    public AgentRuntimeFactory(SessionRepository sessionRepository,
                               ObservabilityRecorder recorder, ObjectMapper objectMapper,
                               ToolMetaRegistry toolMetaRegistry,
                               ToolFailureClassifier toolFailureClassifier,
                               ToolGovernanceDefaults governanceDefaults,
                               TokenEstimator tokenEstimator, SkillRuntime skillRuntime,
                               Executor turnExecutor) {
        this.sessionRepository = sessionRepository;
        this.recorder = recorder;
        this.objectMapper = objectMapper;
        this.toolMetaRegistry = toolMetaRegistry;
        this.toolFailureClassifier = toolFailureClassifier;
        this.governanceDefaults = governanceDefaults;
        this.tokenEstimator = tokenEstimator;
        this.skillRuntime = skillRuntime;
        this.turnExecutor = turnExecutor;
    }

    /**
     * 创建 builder。
     *
     * @param chatModel 模型
     * @return builder
     */
    public AgentRuntimeBuilder builder(ChatModel chatModel) {
        return new AgentRuntimeBuilder(this, chatModel);
    }

    public SessionRepository getSessionRepository() {
        return sessionRepository;
    }

    public ObservabilityRecorder getRecorder() {
        return recorder;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public ToolMetaRegistry getToolMetaRegistry() {
        return toolMetaRegistry;
    }

    public ToolFailureClassifier getToolFailureClassifier() {
        return toolFailureClassifier;
    }

    public ToolGovernanceDefaults getGovernanceDefaults() {
        return governanceDefaults;
    }

    public TokenEstimator getTokenEstimator() {
        return tokenEstimator;
    }

    public SkillRuntime getSkillRuntime() {
        return skillRuntime;
    }

    public Executor getTurnExecutor() {
        return turnExecutor;
    }
}
