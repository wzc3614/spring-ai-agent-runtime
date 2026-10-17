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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;

/**
 * AgentRuntime 工厂（Spring bean，持有会话仓储等基础设施）。
 * 使用者注入此工厂后调用 {@link #builder(ChatModel)} 获得流式 builder。
 */
public class AgentRuntimeFactory {

    private final SessionRepository sessionRepository;

    private final ObservabilityRecorder recorder;

    private final ObjectMapper objectMapper;

    public AgentRuntimeFactory(SessionRepository sessionRepository, ObservabilityRecorder recorder, ObjectMapper objectMapper) {
        this.sessionRepository = sessionRepository;
        this.recorder = recorder;
        this.objectMapper = objectMapper;
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
}
