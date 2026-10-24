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
package com.agentruntime.capability.skill.observe;

import com.agentruntime.api.capability.SkillDescriptor;
import com.agentruntime.api.capability.SkillRouteResult;
import com.agentruntime.api.domain.Span;
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.SkillDeactivateReasonEnum;
import com.agentruntime.api.enums.SpanTypeEnum;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.capability.skill.SkillActivation;
import com.agentruntime.capability.skill.SkillBudgetHit;
import com.agentruntime.capability.skill.SkillConstants;
import com.agentruntime.capability.skill.SkillTurnPlan;
import com.agentruntime.capability.skill.trace.SkillExecutionContext;
import com.agentruntime.capability.skill.trace.SkillToolTrace;
import com.agentruntime.core.springai.SpanContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Skill 生命周期写成 TURN / SKILL span 上的事件。
 * 未命中时只在 TURN 上记 SkillRouted；命中后开 SKILL span，并把后续模型、工具调用的父 span 换成它。
 */
public class SkillTurnObserver {

    private static final Logger logger = LoggerFactory.getLogger(SkillTurnObserver.class);

    private static final String EMPTY_PAYLOAD = "{}";

    private final ObservabilityRecorder recorder;

    private final ObjectMapper objectMapper;

    public SkillTurnObserver(ObservabilityRecorder recorder, ObjectMapper objectMapper) {
        this.recorder = recorder;
        this.objectMapper = objectMapper;
    }

    /**
     * 路由之后、主调用之前。
     *
     * @param turnSpanId TURN span
     * @param sessionId  会话 ID
     * @param plan       本轮计划
     * @return 激活状态。透传计划返回 null
     */
    public SkillActivation begin(String turnSpanId, String sessionId, SkillTurnPlan plan) {
        if (plan == null || plan.isPassthrough()) {
            return null;
        }
        SkillActivation activation = new SkillActivation();
        activation.setPlan(plan);
        if (!plan.isActivated()) {
            recorder.recordEvent(turnSpanId, EventTypeEnum.SKILL_ROUTED, routePayload(plan.getRoute()));
            return activation;
        }
        Span skillSpan = recorder.startSpan(SpanTypeEnum.SKILL, turnSpanId, sessionId);
        applyAttributes(skillSpan, plan);
        activation.setSkillSpan(skillSpan);
        activation.setParentSpanIdForCall(skillSpan.getSpanId());
        SpanContextHolder.set(skillSpan.getSpanId(), sessionId);
        activation.setTraceBuffer(SkillExecutionContext.start());
        String spanId = skillSpan.getSpanId();
        recorder.recordEvent(spanId, EventTypeEnum.SKILL_ROUTED, routePayload(plan.getRoute()));
        recorder.recordEvent(spanId, EventTypeEnum.SKILL_ACTIVATED, activatedPayload(plan));
        recordBudgetHits(spanId, plan.getBudgetHits());
        activation.setStartedAt(Instant.now());
        return activation;
    }

    /**
     * 主调用结束后写 SkillExecuted 和 SkillDeactivated。重复调用只生效一次。
     *
     * @param activation 激活状态
     * @param reason     退出原因
     * @param success    主调用是否成功返回
     */
    public void finish(SkillActivation activation, SkillDeactivateReasonEnum reason, boolean success) {
        if (activation == null || activation.getSkillSpan() == null || !activation.markFinished()) {
            return;
        }
        String spanId = activation.getSkillSpan().getSpanId();
        recorder.recordEvent(spanId, EventTypeEnum.SKILL_EXECUTED, executedPayload(activation, success));
        recorder.recordEvent(spanId, EventTypeEnum.SKILL_DEACTIVATED, deactivatedPayload(activation, reason));
    }

    /**
     * 关闭 SKILL span 并清掉轨迹。重复调用只生效一次。
     *
     * @param activation 激活状态
     */
    public void close(SkillActivation activation) {
        if (activation == null || !activation.markClosed()) {
            return;
        }
        SkillExecutionContext.clear();
        Span skillSpan = activation.getSkillSpan();
        if (skillSpan == null) {
            return;
        }
        SpanContextHolder.set(skillSpan.getParentSpanId(), skillSpan.getSessionId());
        recorder.endSpan(skillSpan.getSpanId());
    }

    private void recordBudgetHits(String spanId, List<SkillBudgetHit> hits) {
        if (CollectionUtils.isEmpty(hits)) {
            return;
        }
        for (SkillBudgetHit hit : hits) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put(SkillConstants.PAYLOAD_SKILL_ID, hit.getSkillId());
            payload.put(SkillConstants.PAYLOAD_STAGE, SkillConstants.STAGE_INSTRUCTION);
            payload.put(SkillConstants.PAYLOAD_ESTIMATED_TOKENS, hit.getEstimatedTokens());
            payload.put(SkillConstants.PAYLOAD_BUDGET, hit.getBudget());
            recorder.recordEvent(spanId, EventTypeEnum.SKILL_LOAD_BUDGET_EXCEEDED, write(payload));
        }
    }

    private void applyAttributes(Span span, SkillTurnPlan plan) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(SkillConstants.ATTR_SKILL_IDS, skillIds(plan.getRoute()));
        if (plan.getRoute() != null && plan.getRoute().getSource() != null) {
            attributes.put(SkillConstants.ATTR_ROUTE_SOURCE, plan.getRoute().getSource().getCode());
        }
        span.setAttributes(write(attributes));
    }

    private String routePayload(SkillRouteResult route) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(SkillConstants.PAYLOAD_CANDIDATES, route == null ? List.of() : route.getCandidates());
        payload.put(SkillConstants.PAYLOAD_SELECTED, skillIds(route));
        payload.put(SkillConstants.PAYLOAD_SOURCE, route == null || route.getSource() == null
                ? null : route.getSource().getCode());
        return write(payload);
    }

    private String activatedPayload(SkillTurnPlan plan) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(SkillConstants.PAYLOAD_SKILL_IDS, skillIds(plan.getRoute()));
        payload.put(SkillConstants.PAYLOAD_INSTRUCTION_TOKENS, plan.getInstructionTokens());
        payload.put(SkillConstants.PAYLOAD_TOOL_NAMES, plan.getActivatedToolNames());
        return write(payload);
    }

    private String executedPayload(SkillActivation activation, boolean success) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(SkillConstants.PAYLOAD_SKILL_IDS, skillIds(activation.getPlan().getRoute()));
        payload.put(SkillConstants.PAYLOAD_TOOL_SEQUENCE, toolSequence());
        payload.put(SkillConstants.PAYLOAD_DURATION_MS, durationMillis(activation.getStartedAt()));
        payload.put(SkillConstants.PAYLOAD_SUCCESS, success);
        return write(payload);
    }

    private String deactivatedPayload(SkillActivation activation, SkillDeactivateReasonEnum reason) {
        Map<String, Object> payload = new LinkedHashMap<>();
        SkillTurnPlan plan = activation.getPlan();
        payload.put(SkillConstants.PAYLOAD_SKILL_IDS, skillIds(plan.getRoute()));
        payload.put(SkillConstants.PAYLOAD_REASON, reason == null ? null : reason.getCode());
        payload.put(SkillConstants.PAYLOAD_ACTIVATION_TOKENS,
                plan.getInstructionTokens() + plan.getActivationToolSchemaTokens());
        return write(payload);
    }

    private List<String> toolSequence() {
        List<String> names = new ArrayList<>();
        for (SkillToolTrace trace : SkillExecutionContext.snapshot()) {
            names.add(trace.getToolName());
        }
        return names;
    }

    private long durationMillis(Instant startedAt) {
        if (startedAt == null) {
            return 0L;
        }
        return Duration.between(startedAt, Instant.now()).toMillis();
    }

    private List<String> skillIds(SkillRouteResult route) {
        List<String> ids = new ArrayList<>();
        if (route == null || CollectionUtils.isEmpty(route.getSelected())) {
            return ids;
        }
        for (SkillDescriptor descriptor : route.getSelected()) {
            ids.add(descriptor.getSkillId());
        }
        return ids;
    }

    private String write(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            logger.warn("skill event payload serialization failed");
            return EMPTY_PAYLOAD;
        }
    }
}
