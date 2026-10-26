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
import com.agentruntime.api.enums.SkillDeactivateReasonEnum;
import com.agentruntime.api.enums.SpanTypeEnum;
import com.agentruntime.api.enums.TurnFrameTypeEnum;
import com.agentruntime.api.exception.TurnAccessException;
import com.agentruntime.api.turn.TurnExecutionConstants;
import com.agentruntime.capability.skill.SkillActivation;
import com.agentruntime.capability.skill.SkillRuntime;
import com.agentruntime.capability.skill.SkillTurnPlan;
import com.agentruntime.capability.skill.trace.SkillExecutionContext;
import com.agentruntime.runtime.hook.TurnHook;
import com.agentruntime.runtime.hook.TurnHookEvents;
import com.agentruntime.runtime.hook.TurnHookGate;
import com.agentruntime.runtime.hook.TurnHookResult;
import com.agentruntime.api.lifecycle.AgentLifecycleEvent;
import com.agentruntime.api.lifecycle.AgentLifecycleListener;
import com.agentruntime.api.lifecycle.AgentLifecycleTypeEnum;
import com.agentruntime.api.lifecycle.SessionInfo;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.api.repository.SessionRepository;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.core.springai.MemoryBudgetContextHolder;
import com.agentruntime.core.springai.SpanContextHolder;
import com.agentruntime.core.springai.TurnContextHolder;
import com.agentruntime.memory.chat.CompactingChatMemory;
import com.agentruntime.runtime.turn.TurnExecutionEngine;
import com.agentruntime.runtime.turn.TurnHandle;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.SignalType;
import reactor.util.context.Context;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Agent 运行时入口。包装 Spring AI {@link ChatClient}，挂载记忆 Advisor，
 * 管理会话生命周期（START / ENDED）并回调监听器。
 *
 * <p>两层 ID：uid（用户）→ sessionId（会话生命周期 + 记忆作用域）。记忆按 session 共享累积，
 * 跨调用持久化。turnId 在内部按"一轮用户交互"生成，不暴露到公开签名。
 * 注册了轮次钩子时，Skill 准备与第一阶段并行，组装 prompt 前执行第二阶段；钩子可以提前返回。
 */
public class AgentRuntime {

    private static final Logger logger = LoggerFactory.getLogger(AgentRuntime.class);

    private final ChatClient chatClient;
    private final String agentName;
    private final String agentVersion;
    private final CompactingChatMemory memory;
    private final AgentLifecycleListener listener;
    private final SessionRepository sessionRepository;
    private final ObservabilityRecorder recorder;
    private final TokenEstimator tokenEstimator;

    /** 包装后的工具（按 prompt 传递，确保进入请求的 tools 字段） */
    private final List<ToolCallback> tools;

    /** 系统提示词（用于估算固定开销） */
    private final String defaultSystem;

    /** 工具 schema 的 token 估算。Skill 透传时复用；Skill 裁剪工具后按本轮工具重算 */
    private final int toolSchemaTokens;

    /** Skill 管道。为空表示未启用，call/stream 保持原行为 */
    private final SkillRuntime skillRuntime;

    /** 本轮业务钩子，按注册顺序执行。为空时 Skill 准备仍在请求线程执行 */
    private final List<TurnHook> turnHooks;

    /** Skill 准备与钩子并行时使用的执行器 */
    private final Executor turnExecutor;

    private final ObjectMapper objectMapper;

    /** 轮次执行。为空或关闭时，call/stream 保持原来的行为 */
    private final TurnExecutionEngine turnExecution;

    private final TurnHookGate turnHookGate = new TurnHookGate();

    /** 会话开始时间，用于计算 duration */
    private final Map<String, Instant> startedAtMap = new ConcurrentHashMap<>();

    /** 会话级 uid 缓存，供 close 时回填生命周期事件 */
    private final Map<String, String> uidBySession = new ConcurrentHashMap<>();

    /** 会话级 SESSION span id */
    private final Map<String, String> sessionSpanIds = new ConcurrentHashMap<>();

    /**
     * @param dependencies 运行依赖。钩子为 null 时按空列表处理
     */
    public AgentRuntime(AgentRuntimeDependencies dependencies) {
        this.chatClient = dependencies.getChatClient();
        this.agentName = dependencies.getAgentName();
        this.agentVersion = dependencies.getAgentVersion();
        this.memory = dependencies.getMemory();
        this.listener = dependencies.getListener();
        this.sessionRepository = dependencies.getSessionRepository();
        this.recorder = dependencies.getRecorder();
        this.tools = dependencies.getTools();
        this.defaultSystem = dependencies.getDefaultSystem();
        this.tokenEstimator = dependencies.getTokenEstimator();
        this.skillRuntime = dependencies.getSkillRuntime();
        List<TurnHook> hooks = dependencies.getTurnHooks();
        this.turnHooks = hooks == null ? List.of() : List.copyOf(hooks);
        this.turnExecutor = dependencies.getTurnExecutor();
        this.objectMapper = dependencies.getObjectMapper();
        this.turnExecution = dependencies.getTurnExecution();
        this.toolSchemaTokens = estimateToolSchemaTokens(this.tools);
        if (turnExecution != null && turnExecution.isEnabled()) {
            turnExecution.register(agentName,
                    (userId, sessionId, query) -> Flux.defer(() -> openStream(userId, sessionId, query)));
        }
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
        if (!executionEnabled()) {
            return executeCall(uid, sessionId, userText);
        }
        prepareTurn(uid, sessionId, userText);
        return turnExecution.call(agentName, uid, sessionId, userText,
                () -> executeCall(uid, sessionId, userText));
    }

    /**
     * 发起一次对话。
     *
     * @param uid       用户 ID
     * @param sessionId  会话 ID（生命周期 + 记忆作用域，记忆跨调用共享累积）
     * @param userText   用户输入
     * @return 模型回复
     */
    private String executeCall(String uid, String sessionId, String userText) {
        ensureStarted(uid, sessionId);
        String sessionSpanId = sessionSpanIds.get(sessionId);
        Span turnSpan = recorder.startSpan(SpanTypeEnum.TURN, sessionSpanId, sessionId);
        SpanContextHolder.set(turnSpan.getSpanId(), sessionId);
        SkillActivation activation = null;
        boolean success = false;
        try {
            TurnHookResult gate = openGate(uid, sessionId, userText);
            if (gate.isInterrupted()) {
                recordInterrupted(turnSpan, gate);
                success = true;
                return gate.getReply();
            }
            SkillTurnPlan plan = gate.getPlan();
            activation = beginSkill(turnSpan, sessionId, plan);
            MemoryBudgetContextHolder.set(estimateFixedOverhead(userText, plan));
            // 工具按 prompt 传递（GA: toolCallbacks(List) 携带回调；tools(Object...) 只收原始对象）
            String reply = applySkill(chatClient.prompt().user(userText), plan)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .call()
                    .content();
            recorder.recordEvent(turnSpan.getSpanId(), EventTypeEnum.FINAL_REPLY, reply);
            success = true;
            return reply;
        } finally {
            finishSync(turnSpan, activation, success);
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
        if (!executionEnabled()) {
            return Flux.defer(() -> openStream(uid, sessionId, userText));
        }
        return streamTurn(uid, sessionId, userText).frames()
                .filter(frame -> frame.getType() == TurnFrameTypeEnum.ANSWER)
                .map(com.agentruntime.api.domain.TurnFrame::getText);
    }

    /**
     * 发起一次可回放的流式对话。轮次执行关闭时，帧只活在这次订阅里。
     *
     * @param uid       用户 ID
     * @param sessionId  会话 ID
     * @param userText   用户输入
     * @return 带 turnId 的帧流
     */
    public TurnHandle streamTurn(String uid, String sessionId, String userText) {
        if (!executionEnabled()) {
            return TurnHandle.live(UUID.randomUUID().toString(),
                    Flux.defer(() -> openStream(uid, sessionId, userText)));
        }
        prepareTurn(uid, sessionId, userText);
        return turnExecution.open(agentName, uid, sessionId, userText,
                (userId, ignoredSessionId, query) -> Flux.defer(() -> openStream(userId, ignoredSessionId, query)));
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
    private Flux<String> openStream(String uid, String sessionId, String userText) {
        ensureStarted(uid, sessionId);
        String sessionSpanId = sessionSpanIds.get(sessionId);
        Span turnSpan = recorder.startSpan(SpanTypeEnum.TURN, sessionSpanId, sessionId);
        SpanContextHolder.set(turnSpan.getSpanId(), sessionId);
        SkillActivation activation = null;
        try {
            TurnHookResult gate = openGate(uid, sessionId, userText);
            if (gate.isInterrupted()) {
                recordInterrupted(turnSpan, gate);
                return Flux.just(gate.getReply())
                        .doFinally(signal -> finishStream(turnSpan, null, signal));
            }
            SkillTurnPlan plan = gate.getPlan();
            activation = beginSkill(turnSpan, sessionId, plan);
            MemoryBudgetContextHolder.set(estimateFixedOverhead(userText, plan));
            StringBuilder accumulated = new StringBuilder();
            SkillActivation started = activation;
            // 工具按 prompt 传递（GA: toolCallbacks(List) 携带回调；tools(Object...) 只收原始对象）
            return applySkill(chatClient.prompt().user(userText), plan)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                    .stream()
                    .content()
                    .doOnNext(accumulated::append)
                    .doOnComplete(() -> recorder.recordEvent(turnSpan.getSpanId(), EventTypeEnum.FINAL_REPLY,
                            accumulated.toString()))
                    .doFinally(signal -> finishStream(turnSpan, started, signal))
                    .contextWrite(turnContext(sessionId, turnSpan, started));
        } catch (RuntimeException ex) {
            finishStream(turnSpan, activation, SignalType.ON_ERROR);
            throw ex;
        }
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
     * 轮次执行前校验 query 和会话归属。会话不存在时先按本次 uid 建出来。
     */
    private void prepareTurn(String uid, String sessionId, String userText) {
        if (StringUtils.isBlank(userText)) {
            throw new TurnAccessException(TurnExecutionConstants.ERROR_EMPTY_QUERY, "query must not be blank");
        }
        ensureStarted(uid, sessionId);
        Optional<AgentSession> existing = sessionRepository.findById(sessionId);
        if (existing.isEmpty()) {
            return;
        }
        String owner = existing.get().getUserId();
        if (StringUtils.isNotBlank(owner) && !owner.equals(uid)) {
            throw new TurnAccessException(TurnExecutionConstants.ERROR_SESSION_UID_MISMATCH,
                    "session does not belong to this uid");
        }
    }

    private boolean executionEnabled() {
        return turnExecution != null && turnExecution.isEnabled();
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

    /**
     * 估算当前轮固定开销 token：系统提示词 + 指令 + 本轮工具 schema + 当前用户输入。
     * 在调用 ChatClient 前写入 {@link MemoryBudgetContextHolder}，供记忆层动态预算计算读取。
     *
     * @param userText 当前用户输入
     * @param plan     Skill 计划。透传时沿用构造期的工具 schema 估算
     * @return 固定开销 token 估算
     */
    private int estimateFixedOverhead(String userText, SkillTurnPlan plan) {
        if (plan == null || plan.isPassthrough()) {
            int systemTokens = defaultSystem == null ? 0 : tokenEstimator.estimate(defaultSystem);
            int userTokens = userText == null ? 0 : tokenEstimator.estimate(userText);
            return systemTokens + toolSchemaTokens + userTokens;
        }
        int systemTokens = plan.getSystemText() == null ? 0 : tokenEstimator.estimate(plan.getSystemText());
        int userTokens = userText == null ? 0 : tokenEstimator.estimate(userText);
        return systemTokens + estimateInstructionTokens(plan.getInstructionMessages())
                + estimateToolSchemaTokens(plan.getTools()) + userTokens;
    }

    private TurnHookResult openGate(String uid, String sessionId, String userText) {
        return turnHookGate.open(uid, sessionId, userText, () -> preparePlan(userText), turnHooks, turnExecutor);
    }

    /**
     * 记录钩子提前返回。日志只保留阶段和原因码，不打印用户原文或回复正文。
     */
    private void recordInterrupted(Span turnSpan, TurnHookResult gate) {
        logger.info("turn interrupted, phase={}, reason={}", gate.getPhase().getCode(), gate.getReasonCode());
        recorder.recordEvent(turnSpan.getSpanId(), EventTypeEnum.TURN_INTERRUPTED,
                TurnHookEvents.payload(objectMapper, gate));
    }

    private SkillTurnPlan preparePlan(String userText) {
        if (skillRuntime == null) {
            return SkillTurnPlan.passthrough(tools);
        }
        return skillRuntime.prepare(userText, defaultSystem, tools);
    }

    private SkillActivation beginSkill(Span turnSpan, String sessionId, SkillTurnPlan plan) {
        if (skillRuntime == null) {
            return null;
        }
        return skillRuntime.begin(turnSpan.getSpanId(), sessionId, plan);
    }

    private ChatClient.ChatClientRequestSpec applySkill(ChatClient.ChatClientRequestSpec spec, SkillTurnPlan plan) {
        if (plan == null || plan.isPassthrough()) {
            if (!CollectionUtils.isEmpty(tools)) {
                return spec.toolCallbacks(tools);
            }
            return spec;
        }
        ChatClient.ChatClientRequestSpec prepared = spec;
        if (plan.getSystemText() != null) {
            prepared = prepared.system(plan.getSystemText());
        }
        if (!CollectionUtils.isEmpty(plan.getInstructionMessages())) {
            prepared = prepared.messages(plan.getInstructionMessages());
        }
        if (!CollectionUtils.isEmpty(plan.getTools())) {
            prepared = prepared.toolCallbacks(plan.getTools());
        }
        return prepared;
    }

    private void finishSync(Span turnSpan, SkillActivation activation, boolean success) {
        try {
            if (skillRuntime != null) {
                SkillDeactivateReasonEnum reason = success
                        ? SkillDeactivateReasonEnum.COMPLETED : SkillDeactivateReasonEnum.ERROR;
                skillRuntime.finish(activation, reason, success);
            }
        } finally {
            closeSkill(turnSpan, activation);
        }
    }

    private void finishStream(Span turnSpan, SkillActivation activation, SignalType signal) {
        try {
            if (skillRuntime != null) {
                skillRuntime.finish(activation, deactivateReason(signal), signal == SignalType.ON_COMPLETE);
            }
        } finally {
            closeSkill(turnSpan, activation);
        }
    }

    private void closeSkill(Span turnSpan, SkillActivation activation) {
        try {
            if (skillRuntime != null) {
                skillRuntime.close(activation);
            }
        } finally {
            MemoryBudgetContextHolder.clear();
            SpanContextHolder.clear();
            recorder.endSpan(turnSpan.getSpanId());
        }
    }

    private SkillDeactivateReasonEnum deactivateReason(SignalType signal) {
        if (signal == SignalType.ON_COMPLETE) {
            return SkillDeactivateReasonEnum.COMPLETED;
        }
        if (signal == SignalType.CANCEL) {
            return SkillDeactivateReasonEnum.CANCELLED;
        }
        return SkillDeactivateReasonEnum.ERROR;
    }

    private Context turnContext(String sessionId, Span turnSpan, SkillActivation activation) {
        String parentSpanId = turnSpan.getSpanId();
        if (activation != null && activation.getParentSpanIdForCall() != null) {
            parentSpanId = activation.getParentSpanIdForCall();
        }
        Context context = Context.of(
                SpanContextHolder.CONTEXT_KEY_PARENT_SPAN_ID, parentSpanId,
                SpanContextHolder.CONTEXT_KEY_SESSION_ID, sessionId);
        String turnId = TurnContextHolder.turnId();
        if (turnId != null) {
            context = context.put(TurnContextHolder.CONTEXT_KEY, turnId);
        }
        if (activation != null && activation.getTraceBuffer() != null) {
            context = context.put(SkillExecutionContext.CONTEXT_KEY, activation.getTraceBuffer());
        }
        return context;
    }

    private int estimateInstructionTokens(List<Message> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            return 0;
        }
        int total = 0;
        for (Message message : messages) {
            total += tokenEstimator.estimate(message.getText());
        }
        return total;
    }

    /**
     * 估算一组工具的 schema token（name + description + inputSchema JSON）。
     * 透传路径在构造时算一次；Skill 裁剪工具后按本轮工具重算。
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
