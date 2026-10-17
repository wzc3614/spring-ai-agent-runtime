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

import com.agentruntime.api.domain.AgentSession;
import com.agentruntime.api.domain.Span;
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.SessionStatusEnum;
import com.agentruntime.api.enums.SpanTypeEnum;
import com.agentruntime.api.lifecycle.AgentLifecycleEvent;
import com.agentruntime.api.lifecycle.AgentLifecycleListener;
import com.agentruntime.api.lifecycle.AgentLifecycleTypeEnum;
import com.agentruntime.api.lifecycle.SessionInfo;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.api.repository.SessionRepository;
import com.agentruntime.api.spill.SpillConfig;
import com.agentruntime.core.springai.SpanContextHolder;
import com.agentruntime.memory.chat.CompactingChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 运行时入口。包装 Spring AI {@link ChatClient}，挂载记忆 Advisor，
 * 管理会话生命周期（START / ENDED）并回调监听器。
 */
public class AgentRuntime {

    private final ChatClient chatClient;
    private final String agentName;
    private final String agentVersion;
    private final CompactingChatMemory memory;
    private final SpillConfig spillConfig;
    private final AgentLifecycleListener listener;
    private final SessionRepository sessionRepository;
    private final ObservabilityRecorder recorder;

    /** 包装后的工具（按 prompt 传递，确保进入请求的 tools 字段） */
    private final List<ToolCallback> tools;

    /** 会话开始时间，用于计算 duration */
    private final Map<String, Instant> startedAtMap = new ConcurrentHashMap<>();

    /** 会话级 SESSION span id */
    private final Map<String, String> sessionSpanIds = new ConcurrentHashMap<>();

    public AgentRuntime(ChatClient chatClient, String agentName, String agentVersion,
                        CompactingChatMemory memory, SpillConfig spillConfig,
                        AgentLifecycleListener listener, SessionRepository sessionRepository,
                        ObservabilityRecorder recorder, List<ToolCallback> tools) {
        this.chatClient = chatClient;
        this.agentName = agentName;
        this.agentVersion = agentVersion;
        this.memory = memory;
        this.spillConfig = spillConfig;
        this.listener = listener;
        this.sessionRepository = sessionRepository;
        this.recorder = recorder;
        this.tools = tools;
    }

    /**
     * 发起一次对话。
     *
     * @param sessionId 会话 ID
     * @param userText  用户输入
     * @return 模型回复
     */
    public String call(String sessionId, String userText) {
        ensureStarted(sessionId);
        String sessionSpanId = sessionSpanIds.get(sessionId);
        Span turnSpan = recorder.startSpan(SpanTypeEnum.TURN, sessionSpanId, sessionId);
        SpanContextHolder.set(turnSpan.getSpanId(), sessionId);
        try {
            // 工具按 prompt 传递（GA: toolCallbacks(List) 携带回调；tools(Object...) 只收原始对象）
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt().user(userText);
            if (!CollectionUtils.isEmpty(tools)) {
                spec = spec.toolCallbacks(tools);
            }
            String reply = spec
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .content();
            recorder.recordEvent(turnSpan.getSpanId(), EventTypeEnum.FINAL_REPLY, reply);
            return reply;
        } finally {
            SpanContextHolder.clear();
            recorder.endSpan(turnSpan.getSpanId());
        }
    }

    /**
     * 结束会话，触发 ENDED 事件并计算 duration。
     *
     * @param sessionId 会话 ID
     */
    public void close(String sessionId) {
        Instant startedAt = startedAtMap.remove(sessionId);
        Instant now = Instant.now();
        Duration duration = startedAt != null ? Duration.between(startedAt, now) : Duration.ZERO;
        sessionRepository.updateStatus(sessionId, SessionStatusEnum.ENDED);
        fireLifecycle(sessionId, AgentLifecycleTypeEnum.ENDED, now, startedAt, now, duration);
        String sessionSpanId = sessionSpanIds.remove(sessionId);
        if (sessionSpanId != null) {
            recorder.endSpan(sessionSpanId);
        }
    }

    private void ensureStarted(String sessionId) {
        if (startedAtMap.containsKey(sessionId)) {
            return;
        }
        Instant now = Instant.now();
        Optional<AgentSession> existing = sessionRepository.findById(sessionId);
        if (existing.isEmpty()) {
            AgentSession session = new AgentSession();
            session.setSessionId(sessionId);
            session.setAgentName(agentName);
            session.setStatus(SessionStatusEnum.ACTIVE);
            session.setCreatedAt(now);
            session.setLastActiveAt(now);
            sessionRepository.save(session);
        }
        startedAtMap.put(sessionId, now);
        Span sessionSpan = recorder.startSpan(SpanTypeEnum.SESSION, null, sessionId);
        sessionSpanIds.put(sessionId, sessionSpan.getSpanId());
        fireLifecycle(sessionId, AgentLifecycleTypeEnum.START, now, now, null, null);
    }

    private void fireLifecycle(String sessionId, AgentLifecycleTypeEnum type, Instant occurredAt,
                               Instant startedAt, Instant endedAt, Duration duration) {
        if (listener == null) {
            return;
        }
        SessionInfo info = new SessionInfo();
        info.setSessionId(sessionId);
        info.setAgentName(agentName);
        info.setAgentVersion(agentVersion);
        info.setStartedAt(startedAt);
        info.setEndedAt(endedAt);
        info.setDuration(duration);

        AgentLifecycleEvent event = new AgentLifecycleEvent();
        event.setSessionId(sessionId);
        event.setType(type);
        event.setOccurredAt(occurredAt);
        event.setSession(info);
        listener.onEvent(event);
    }

    public String getAgentName() {
        return agentName;
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public CompactingChatMemory getMemory() {
        return memory;
    }

    public SpillConfig getSpillConfig() {
        return spillConfig;
    }
}
