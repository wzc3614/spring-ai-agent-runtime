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
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.api.repository.SessionRepository;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.capability.skill.SkillRuntime;
import com.agentruntime.memory.chat.CompactingChatMemory;
import com.agentruntime.runtime.hook.TurnHook;
import com.agentruntime.runtime.turn.TurnExecutionEngine;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * {@link AgentRuntime} 的构造依赖。后续新增依赖加字段即可，构造签名保持不变。
 */
public class AgentRuntimeDependencies {

    private ChatClient chatClient;

    /** agent 名称，崩溃恢复按它过滤 */
    private String agentName;

    private String agentVersion;

    private CompactingChatMemory memory;

    private AgentLifecycleListener listener;

    private SessionRepository sessionRepository;

    private ObservabilityRecorder recorder;

    /** 包装后的工具（按 prompt 传递，确保进入请求的 tools 字段） */
    private List<ToolCallback> tools;

    /** 系统提示词（用于估算固定开销） */
    private String defaultSystem;

    private TokenEstimator tokenEstimator;

    /** Skill 管道。为空表示未启用 */
    private SkillRuntime skillRuntime;

    /** 本轮业务钩子，按注册顺序执行 */
    private List<TurnHook> turnHooks;

    /** Skill 准备与钩子并行时使用的执行器 */
    private Executor turnExecutor;

    private ObjectMapper objectMapper;

    /** 轮次执行。为空或关闭时，call/stream 保持原来的行为 */
    private TurnExecutionEngine turnExecution;

    public ChatClient getChatClient() {
        return chatClient;
    }

    public void setChatClient(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(String agentVersion) {
        this.agentVersion = agentVersion;
    }

    public CompactingChatMemory getMemory() {
        return memory;
    }

    public void setMemory(CompactingChatMemory memory) {
        this.memory = memory;
    }

    public AgentLifecycleListener getListener() {
        return listener;
    }

    public void setListener(AgentLifecycleListener listener) {
        this.listener = listener;
    }

    public SessionRepository getSessionRepository() {
        return sessionRepository;
    }

    public void setSessionRepository(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    public ObservabilityRecorder getRecorder() {
        return recorder;
    }

    public void setRecorder(ObservabilityRecorder recorder) {
        this.recorder = recorder;
    }

    public List<ToolCallback> getTools() {
        return tools;
    }

    public void setTools(List<ToolCallback> tools) {
        this.tools = tools;
    }

    public String getDefaultSystem() {
        return defaultSystem;
    }

    public void setDefaultSystem(String defaultSystem) {
        this.defaultSystem = defaultSystem;
    }

    public TokenEstimator getTokenEstimator() {
        return tokenEstimator;
    }

    public void setTokenEstimator(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    public SkillRuntime getSkillRuntime() {
        return skillRuntime;
    }

    public void setSkillRuntime(SkillRuntime skillRuntime) {
        this.skillRuntime = skillRuntime;
    }

    public List<TurnHook> getTurnHooks() {
        return turnHooks;
    }

    public void setTurnHooks(List<TurnHook> turnHooks) {
        this.turnHooks = turnHooks;
    }

    public Executor getTurnExecutor() {
        return turnExecutor;
    }

    public void setTurnExecutor(Executor turnExecutor) {
        this.turnExecutor = turnExecutor;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public TurnExecutionEngine getTurnExecution() {
        return turnExecution;
    }

    public void setTurnExecution(TurnExecutionEngine turnExecution) {
        this.turnExecution = turnExecution;
    }
}
