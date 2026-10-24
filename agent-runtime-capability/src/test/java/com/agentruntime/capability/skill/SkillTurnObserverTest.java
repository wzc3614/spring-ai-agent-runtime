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
package com.agentruntime.capability.skill;

import com.agentruntime.api.capability.SkillDescriptor;
import com.agentruntime.api.capability.SkillRouteResult;
import com.agentruntime.api.domain.EventRecord;
import com.agentruntime.api.domain.Span;
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.SkillDeactivateReasonEnum;
import com.agentruntime.api.enums.SkillRouteSourceEnum;
import com.agentruntime.api.enums.SpanTypeEnum;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.capability.skill.observe.SkillTurnObserver;
import com.agentruntime.capability.skill.trace.SkillExecutionContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTurnObserverTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void activatedSkillNestsEventsUnderSkillSpan() throws Exception {
        RecordingRecorder recorder = new RecordingRecorder();
        SkillTurnObserver observer = new SkillTurnObserver(recorder, objectMapper);
        SkillTurnPlan plan = activatedPlan();
        SkillBudgetHit hit = new SkillBudgetHit();
        hit.setSkillId("order-query");
        hit.setEstimatedTokens(50);
        hit.setBudget(40);
        plan.setBudgetHits(List.of(hit));

        SkillActivation activation = observer.begin("turn-1", "session-1", plan);
        SkillExecutionContext.record("query_recent_orders", true, 12L);
        observer.finish(activation, SkillDeactivateReasonEnum.COMPLETED, true);
        observer.finish(activation, SkillDeactivateReasonEnum.COMPLETED, true);
        observer.close(activation);

        Span skillSpan = recorder.spans.get(0);
        assertEquals(SpanTypeEnum.SKILL, skillSpan.getSpanType());
        assertEquals("turn-1", skillSpan.getParentSpanId());
        assertEquals(List.of(
                EventTypeEnum.SKILL_ROUTED,
                EventTypeEnum.SKILL_ACTIVATED,
                EventTypeEnum.SKILL_LOAD_BUDGET_EXCEEDED,
                EventTypeEnum.SKILL_EXECUTED,
                EventTypeEnum.SKILL_DEACTIVATED), types(skillSpan.getSpanId(), recorder.events));

        JsonNode executed = objectMapper.readTree(payload(recorder.events, EventTypeEnum.SKILL_EXECUTED));
        assertEquals("query_recent_orders", executed.get(SkillConstants.PAYLOAD_TOOL_SEQUENCE).get(0).asText());
        assertTrue(executed.get(SkillConstants.PAYLOAD_SUCCESS).asBoolean());
        JsonNode deactivated = objectMapper.readTree(payload(recorder.events, EventTypeEnum.SKILL_DEACTIVATED));
        assertEquals(SkillDeactivateReasonEnum.COMPLETED.getCode(),
                deactivated.get(SkillConstants.PAYLOAD_REASON).asText());
        assertTrue(recorder.ended.contains(skillSpan.getSpanId()));
    }

    @Test
    void missRecordsRoutedOnTurnAndDoesNotOpenSkillSpan() {
        RecordingRecorder recorder = new RecordingRecorder();
        SkillTurnObserver observer = new SkillTurnObserver(recorder, objectMapper);
        SkillTurnPlan plan = new SkillTurnPlan();
        plan.setPassthrough(false);
        plan.setActivated(false);
        SkillRouteResult route = new SkillRouteResult();
        route.setCandidates(List.of("order-query"));
        route.setSelected(List.of());
        route.setSource(SkillRouteSourceEnum.NONE);
        plan.setRoute(route);

        SkillActivation activation = observer.begin("turn-1", "session-1", plan);
        observer.finish(activation, SkillDeactivateReasonEnum.COMPLETED, true);
        observer.close(activation);

        assertTrue(recorder.spans.isEmpty());
        assertEquals(1, recorder.events.size());
        assertEquals("turn-1", recorder.events.get(0).getSpanId());
        assertEquals(EventTypeEnum.SKILL_ROUTED, recorder.events.get(0).getEventType());
    }

    @Test
    void passthroughOpensNothing() {
        RecordingRecorder recorder = new RecordingRecorder();
        SkillTurnObserver observer = new SkillTurnObserver(recorder, objectMapper);
        assertNull(observer.begin("turn-1", "session-1", SkillTurnPlan.passthrough(List.of())));
    }

    private SkillTurnPlan activatedPlan() {
        SkillDescriptor descriptor = new SkillDescriptor();
        descriptor.setSkillId("order-query");
        SkillRouteResult route = new SkillRouteResult();
        route.setCandidates(List.of("order-query"));
        route.setSelected(List.of(descriptor));
        route.setSource(SkillRouteSourceEnum.RULE);
        SkillTurnPlan plan = new SkillTurnPlan();
        plan.setPassthrough(false);
        plan.setActivated(true);
        plan.setRoute(route);
        plan.setInstructionTokens(8);
        plan.setActivationToolSchemaTokens(3);
        plan.setActivatedToolNames(List.of("query_recent_orders"));
        return plan;
    }

    private List<EventTypeEnum> types(String spanId, List<EventRecord> events) {
        List<EventTypeEnum> types = new ArrayList<>();
        for (EventRecord event : events) {
            if (spanId.equals(event.getSpanId())) {
                types.add(event.getEventType());
            }
        }
        return types;
    }

    private String payload(List<EventRecord> events, EventTypeEnum type) {
        for (EventRecord event : events) {
            if (event.getEventType() == type) {
                return event.getPayload();
            }
        }
        return null;
    }

    private static final class RecordingRecorder implements ObservabilityRecorder {

        private final List<Span> spans = new ArrayList<>();

        private final List<EventRecord> events = new ArrayList<>();

        private final List<String> ended = new ArrayList<>();

        @Override
        public Span startSpan(SpanTypeEnum type, String parentSpanId, String sessionId) {
            Span span = new Span();
            span.setSpanId(UUID.randomUUID().toString());
            span.setParentSpanId(parentSpanId);
            span.setSessionId(sessionId);
            span.setSpanType(type);
            spans.add(span);
            return span;
        }

        @Override
        public void endSpan(String spanId) {
            ended.add(spanId);
        }

        @Override
        public void recordEvent(String spanId, EventTypeEnum type, String payload) {
            EventRecord event = new EventRecord();
            event.setSpanId(spanId);
            event.setEventType(type);
            event.setPayload(payload);
            events.add(event);
        }
    }
}
