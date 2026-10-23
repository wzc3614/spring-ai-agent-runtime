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
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.core.springai.MemoryBudgetContextHolder;
import com.agentruntime.core.springai.SpanContextHolder;
import com.agentruntime.memory.chat.CompactingChatMemory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.util.context.Context;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 运行时入口。包装 Spring AI {@link ChatClient}，挂载记忆 Advisor，
 * 管理会话生命周期（START / ENDED）并回调监听器。
 *
 * <p>两层 ID：uid（用户）→ sessionId（会话生命周期 + 记忆作用域）。记忆按 session 共享累积，
 * 跨调用持久化。turnId 在内部按"一轮用户交互"生成，不暴露到公开签名。
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
    private final TokenEstimator tokenEstimator;

    /** 包装后的工具（按 prompt 传递，确保进入请求的 tools 字段） */
    private final List<ToolCallback> tools;

    /** 系统提示词（用于估算固定开销） */
    private final String defaultSystem;

    /** 工具 schema 的 token 估算（构造时一次性计算，避免每轮重复估算） */
    private final int toolSchemaTokens;

    /** 会话开始时间，用于计算 duration */
    private final Map<String, Instant> startedAtMap = new ConcurrentHashMap<>();

    /** 会话级 uid 缓存，供 close 时回填生命周期事件 */
    private final Map<String, String> uidBySession = new ConcurrentHashMap<>();

    /** 会话级 SESSION span id */
    private final Map<String, String> sessionSpanIds = new ConcurrentHashMap<>();

    public AgentRuntime(ChatClient chatClient, String agentName, String agentVersion,
                        CompactingChatMemory memory, SpillConfig spillConfig,
                        AgentLifecycleListener listener, SessionRepository sessionRepository,
                        ObservabilityRecorder recorder, List<ToolCallback> tools,
                        String defaultSystem, TokenEstimator tokenEstimator) {
        this.chatClient = chatClient;
        this.agentName = agentName;
        this.agentVersion = agentVersion;
        this.memory = memory;
        this.spillConfig = spillConfig;
        this.listener = listener;
        this.sessionRepository = sessionRepository;
        this.recorder = recorder;
        this.tools = tools;
        this.defaultSystem = defaultSystem;
        this.tokenEstimator = tokenEstimator;
        this.toolSchemaTokens = estimateToolSchemaTokens(tools);
    }

    /**
     * 发起一次对话。
     *
     * @param uid       用户 ID
     * @param sessionId  会话 ID（生命周期 + 记忆作用域，记忆跨调用共享累积）
     * @param userText   用户输入
     * @return 模型回复
     */
    public String call(String uid, String sessionId, String userText) {
        ensureStarted(uid, sessionId);
        String sessionSpanId = sessionSpanIds.get(sessionId);
        Span turnSpan = recorder.startSpan(SpanTypeEnum.TURN, sessionSpanId, sessionId);
        SpanContextHolder.set(turnSpan.getSpanId(), sessionId);
        MemoryBudgetContextHolder.set(estimateFixedOverhead(userText));
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
            MemoryBudgetContextHolder.clear();
            SpanContextHolder.clear();
            recorder.endSpan(turnSpan.getSpanId());
        }
    }

    /**
     * 发起一次流式对话，按 token 增量返回模型回复。
     *
     * <p>TURN span 在订阅时开始、在流终止（complete/error/cancel）时结束；完整回复累积后记为
     * {@code FINAL_REPLY} 事件。span 上下文经 {@code contextWrite} 写入 Reactor Context，
     * 配合自动上下文传播，工具装饰器在 reactor 线程上也能正确建 TOOL_CALL span。
     *
     * @param uid       用户 ID
     * @param sessionId  会话 ID（生命周期 + 记忆作用域，记忆跨调用共享累积）
     * @param userText   用户输入
     * @return 模型回复 token 流
     */
    public Flux<String> stream(String uid, String sessionId, String userText) {
        return Flux.defer(() -> {
            ensureStarted(uid, sessionId);
            String sessionSpanId = sessionSpanIds.get(sessionId);
            Span turnSpan = recorder.startSpan(SpanTypeEnum.TURN, sessionSpanId, sessionId);
            SpanContextHolder.set(turnSpan.getSpanId(), sessionId);
            MemoryBudgetContextHolder.set(estimateFixedOverhead(userText));
            StringBuilder accumulated = new StringBuilder();
            // 工具按 prompt 传递（GA: toolCallbacks(List) 携带回调；tools(Object...) 只收原始对象）
            ChatClient.ChatClientRequestSpec spec = chatClient.prompt().user(userText);
            if (!CollectionUtils.isEmpty(tools)) {
                spec = spec.toolCallbacks(tools);
            }
            return spec
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .stream()
                    .content()
                    .doOnNext(accumulated::append)
                    .doOnComplete(() -> recorder.recordEvent(turnSpan.getSpanId(), EventTypeEnum.FINAL_REPLY,
                            accumulated.toString()))
                    .doFinally(s -> {
                        MemoryBudgetContextHolder.clear();
                        SpanContextHolder.clear();
                        recorder.endSpan(turnSpan.getSpanId());
                    })
                    .contextWrite(Context.of(
                            SpanContextHolder.CONTEXT_KEY_PARENT_SPAN_ID, turnSpan.getSpanId(),
                            SpanContextHolder.CONTEXT_KEY_SESSION_ID, sessionId));
        });
    }

    /**
     * 结束会话，触发 ENDED 事件并计算 duration。
     *
     * @param sessionId 会话 ID
     */
    public void close(String sessionId) {
        Instant startedAt = startedAtMap.remove(sessionId);
        String uid = uidBySession.remove(sessionId);
        Instant now = Instant.now();
        Duration duration = startedAt != null ? Duration.between(startedAt, now) : Duration.ZERO;
        sessionRepository.updateStatus(sessionId, SessionStatusEnum.ENDED);
        fireLifecycle(sessionId, AgentLifecycleTypeEnum.ENDED, now, startedAt, now, duration, uid);
        String sessionSpanId = sessionSpanIds.remove(sessionId);
        if (sessionSpanId != null) {
            recorder.endSpan(sessionSpanId);
        }
    }

    /**
     * 幂等启动会话：确保 session 记录存在（带 uid）且开 SESSION span，触发 START 生命周期。
     */
    private void ensureStarted(String uid, String sessionId) {
        if (startedAtMap.containsKey(sessionId)) {
            return;
        }
        Instant now = Instant.now();
        Optional<AgentSession> existing = sessionRepository.findById(sessionId);
        String effectiveUid;
        if (existing.isEmpty()) {
            AgentSession session = new AgentSession();
            session.setSessionId(sessionId);
            session.setAgentName(agentName);
            session.setUserId(uid);
            session.setStatus(SessionStatusEnum.ACTIVE);
            session.setCreatedAt(now);
            session.setLastActiveAt(now);
            sessionRepository.save(session);
            effectiveUid = uid;
        } else {
            // 会话已存在（跨实例续接）：优先用既有 uid，避免本次调用传 null 覆盖
            effectiveUid = existing.get().getUserId() != null ? existing.get().getUserId() : uid;
        }
        startedAtMap.put(sessionId, now);
        uidBySession.put(sessionId, effectiveUid);
        Span sessionSpan = recorder.startSpan(SpanTypeEnum.SESSION, null, sessionId);
        sessionSpanIds.put(sessionId, sessionSpan.getSpanId());
        fireLifecycle(sessionId, AgentLifecycleTypeEnum.START, now, now, null, null, effectiveUid);
    }

    private void fireLifecycle(String sessionId, AgentLifecycleTypeEnum type, Instant occurredAt,
                               Instant startedAt, Instant endedAt, Duration duration, String uid) {
        if (listener == null) {
            return;
        }
        SessionInfo info = new SessionInfo();
        info.setSessionId(sessionId);
        info.setUserId(uid);
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

    /**
     * 估算当前轮固定开销 token：系统提示词 + 工具 schema + 当前用户输入。
     * 在调用 ChatClient 前写入 {@link MemoryBudgetContextHolder}，供记忆层动态预算计算读取。
     *
     * @param userText 当前用户输入
     * @return 固定开销 token 估算
     */
    private int estimateFixedOverhead(String userText) {
        int systemTokens = defaultSystem == null ? 0 : tokenEstimator.estimate(defaultSystem);
        int userTokens = userText == null ? 0 : tokenEstimator.estimate(userText);
        return systemTokens + toolSchemaTokens + userTokens;
    }

    /**
     * 估算一组工具的 schema token（name + description + inputSchema JSON）。
     * 构造时一次性计算，避免每轮重复估算。
     */
    private int estimateToolSchemaTokens(List<ToolCallback> callbacks) {
        if (CollectionUtils.isEmpty(callbacks)) {
            return 0;
        }
        int total = 0;
        for (ToolCallback callback : callbacks) {
            org.springframework.ai.tool.definition.ToolDefinition def = callback.getToolDefinition();
            total += tokenEstimator.estimate(def.name());
            total += tokenEstimator.estimate(def.description());
            total += tokenEstimator.estimateJson(def.inputSchema());
        }
        return total;
    }
}
