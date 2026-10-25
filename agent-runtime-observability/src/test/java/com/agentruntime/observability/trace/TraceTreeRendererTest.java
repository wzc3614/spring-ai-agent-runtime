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
package com.agentruntime.observability.trace;

import com.agentruntime.api.domain.AgentSession;
import com.agentruntime.api.domain.EventRecord;
import com.agentruntime.api.domain.Span;
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.SpanTypeEnum;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 调用树只展开选中的一轮，并按开始时间排列子 span。
 */
class TraceTreeRendererTest {

    private static final Instant START = Instant.parse("2026-10-25T00:00:00Z");

    private final TraceTreeRenderer renderer = new TraceTreeRenderer(new ObjectMapper());

    @Test
    void rendersSelectedTurnWithModelAndToolDetails() {
        Span turn = span("t1", "session", SpanTypeEnum.TURN, null, START, START.plusMillis(20));
        Span model = span("m1", "t1", SpanTypeEnum.MODEL_CALL,
                "{\"modelName\":\"qwen\",\"finishReason\":\"STOP\",\"inputTokens\":10,\"outputTokens\":2}",
                START.plusMillis(1), START.plusMillis(6));
        String output = "line1\nline2";
        Map<String, List<EventRecord>> events = new HashMap<>();
        events.put("m1", List.of(event("e-final", "m1", EventTypeEnum.FINAL_REPLY, "SECRET_REPLY", START)));
        events.put("tool-late", List.of(
                event("e-out", "tool-late", EventTypeEnum.TOOL_OUTPUT, output, START.plusMillis(3)),
                event("e-in", "tool-late", EventTypeEnum.TOOL_INPUT, "{\"userId\":\"u1\"}", START.plusMillis(2)),
                event("e-err", "tool-late", EventTypeEnum.ERROR, "timeout", START.plusMillis(4))));
        events.put("t1", List.of(event("e-budget", "t1", EventTypeEnum.TURN_BUDGET_EXCEEDED,
                "{\"reason\":\"STEP_LIMIT\"}", START.plusMillis(19))));

        TraceTreeView view = renderer.render(session(), List.of(
                span("tool-late", "t1", SpanTypeEnum.TOOL_CALL, "{\"toolName\":\"query_user_info\"}",
                        START.plusMillis(8), START.plusMillis(12)),
                span("compact", null, SpanTypeEnum.COMPACTION, null, START, START.plusMillis(1)),
                model,
                span("tool-early", "t1", SpanTypeEnum.TOOL_CALL, "{\"toolName\":\"say \\\"hi\\\"\"}",
                        START.plusMillis(7), START.plusMillis(9)),
                turn), events, "t1");

        assertThat(view.getSelectedTurnId()).isEqualTo("t1");
        assertThat(view.getNotice()).isNull();
        assertThat(view.getTree()).isEqualTo("""
                SESSION  agentName="Demo"  userId="u1"  sessionId="s1"
                │
                └── TURN
                    durationMs=20
                    budget={"reason":"STEP_LIMIT"}
                    │
                    ├── MODEL_CALL #1
                    │   modelName="qwen"
                    │   finishReason="STOP"
                    │   inputTokens=10, outputTokens=2
                    │   durationMs=5
                    │
                    ├── TOOL_CALL
                    │   toolName="say \\"hi\\""
                    │   durationMs=2
                    │
                    └── TOOL_CALL
                        toolName="query_user_info"
                        input={"userId":"u1"}
                        output=line1 line2
                        durationMs=4
                        error=timeout
                """);
        assertThat(view.getTree()).doesNotContain("SECRET_REPLY");
        assertThat(view.getTree()).doesNotContain("COMPACTION");
        assertThat(view.getTree()).doesNotContain("cost");
    }

    @Test
    void numbersModelCallsUnderSkillAndKeepsLatestTurnByDefault() {
        Span first = span("t1", "session", SpanTypeEnum.TURN, null, START, START.plusMillis(5));
        Span second = span("t2", "session", SpanTypeEnum.TURN, null, START.plusMillis(10), START.plusMillis(30));
        Span skill = span("skill", "t2", SpanTypeEnum.SKILL,
                "{\"skillIds\":[\"order-query\"],\"routeSource\":\"keyword\"}",
                START.plusMillis(11), START.plusMillis(20));
        Span inside = span("m-inside", "skill", SpanTypeEnum.MODEL_CALL, "{\"modelName\":\"inside\"}",
                START.plusMillis(12), START.plusMillis(13));
        Span after = span("m-after", "t2", SpanTypeEnum.MODEL_CALL, "{\"modelName\":\"after\"}",
                START.plusMillis(21), START.plusMillis(22));
        Span hidden = span("m-hidden", "t1", SpanTypeEnum.MODEL_CALL, "{\"modelName\":\"hidden\"}",
                START.plusMillis(1), START.plusMillis(2));

        TraceTreeView latest = renderer.render(session(), List.of(hidden, first, after, skill, inside, second),
                Map.of(), null);

        assertThat(latest.getTurns()).extracting(TraceTurnRef::getSpanId).containsExactly("t1", "t2");
        assertThat(latest.getTurns()).extracting(TraceTurnRef::getIndex).containsExactly(1, 2);
        assertThat(latest.getSelectedTurnId()).isEqualTo("t2");
        assertThat(latest.getTree()).contains("SKILL");
        assertThat(latest.getTree()).contains("skillIds=\"[order-query]\"");
        assertThat(latest.getTree()).contains("routeSource=\"keyword\"");
        assertThat(latest.getTree().indexOf("MODEL_CALL #1")).isLessThan(latest.getTree().indexOf("modelName=\"inside\""));
        assertThat(latest.getTree().indexOf("modelName=\"inside\"")).isLessThan(latest.getTree().indexOf("MODEL_CALL #2"));
        assertThat(latest.getTree()).contains("modelName=\"after\"");
        assertThat(latest.getTree()).doesNotContain("hidden");

        TraceTreeView requested = renderer.render(session(), List.of(first, second, hidden), Map.of(), "t1");
        assertThat(requested.getSelectedTurnId()).isEqualTo("t1");
        assertThat(requested.getTree()).contains("modelName=\"hidden\"");
        assertThat(requested.getTree()).doesNotContain("MODEL_CALL #2");
    }

    @Test
    void truncatesLongValuesAndIgnoresBrokenAttributes() {
        String payload = "x".repeat(TraceTreeConstants.MAX_VALUE_CHARS + 1);
        Span turn = span("t1", "session", SpanTypeEnum.TURN, null, START, START.plusMillis(3));
        Span tool = span("tool", "t1", SpanTypeEnum.TOOL_CALL, "{", START.plusMillis(1), START.plusMillis(2));
        Map<String, List<EventRecord>> events = Map.of("tool",
                List.of(event("e1", "tool", EventTypeEnum.TOOL_OUTPUT, payload, START)));

        TraceTreeView view = renderer.render(session(), List.of(turn, tool), events, "t1");

        assertThat(view.getTree()).contains("output=" + "x".repeat(TraceTreeConstants.MAX_VALUE_CHARS) + "...");
        assertThat(view.getTree()).doesNotContain("toolName=");
        assertThat(view.getTree()).contains("TOOL_CALL");
    }

    @Test
    void reportsMissingSessionTurnAndUnknownTurn() {
        TraceTreeView missing = renderer.render(null, List.of(), Map.of(), null);
        assertThat(missing.getNotice()).isEqualTo(TraceTreeConstants.NOTICE_SESSION_NOT_FOUND);
        assertThat(missing.getTree()).isEmpty();

        TraceTreeView noTurn = renderer.render(session(), List.of(
                span("session", null, SpanTypeEnum.SESSION, null, START, START)), Map.of(), null);
        assertThat(noTurn.getNotice()).isEqualTo(TraceTreeConstants.NOTICE_NO_TURN);
        assertThat(noTurn.getTree()).startsWith("SESSION  agentName=\"Demo\"");
        assertThat(noTurn.getSelectedTurnId()).isNull();

        Span turn = span("t1", "session", SpanTypeEnum.TURN, null, START, START);
        TraceTreeView unknown = renderer.render(session(), List.of(turn), Map.of(), "missing");
        assertThat(unknown.getNotice()).isEqualTo(TraceTreeConstants.NOTICE_TURN_NOT_FOUND);
        assertThat(unknown.getTurns()).hasSize(1);
        assertThat(unknown.getSelectedTurnId()).isNull();
        assertThat(unknown.getTree()).doesNotContain("TURN");
    }

    @Test
    void showsThinkingAndResponseOnce() {
        Span turn = span("t1", "session", SpanTypeEnum.TURN, null, START, START.plusMillis(10));
        Span first = span("m1", "t1", SpanTypeEnum.MODEL_CALL, "{\"modelName\":\"qwen\"}",
                START.plusMillis(1), START.plusMillis(2));
        Span second = span("m2", "t1", SpanTypeEnum.MODEL_CALL, "{\"modelName\":\"qwen\"}",
                START.plusMillis(3), START.plusMillis(4));
        String answer = "结论：已查到";
        Map<String, List<EventRecord>> events = new HashMap<>();
        events.put("t1", List.of(event("reply", "t1", EventTypeEnum.FINAL_REPLY, "第一行\n" + answer, START)));
        events.put("m1", List.of(event("think", "m1", EventTypeEnum.THINKING, "先查用户\n再看等级", START)));
        events.put("m2", List.of(event("same", "m2", EventTypeEnum.THINKING, "第一行\n" + answer, START)));

        TraceTreeView view = renderer.render(session(), List.of(turn, second, first), events, "t1");

        assertThat(view.getTree()).contains("response=第一行\n    " + answer);
        assertThat(view.getTree()).contains("thinking=先查用户\n    │   再看等级");
        assertThat(view.getTree()).doesNotContain("thinking=第一行");
    }

    @Test
    void truncatesNarrativeLaterThanToolOutput() {
        Span turn = span("t1", "session", SpanTypeEnum.TURN, null, START, START.plusMillis(3));
        Span model = span("m1", "t1", SpanTypeEnum.MODEL_CALL, null, START.plusMillis(1), START.plusMillis(2));
        String thinking = "t".repeat(TraceTreeConstants.MAX_NARRATIVE_CHARS + 1);
        Map<String, List<EventRecord>> events = Map.of("m1",
                List.of(event("think", "m1", EventTypeEnum.THINKING, thinking, START)));

        TraceTreeView view = renderer.render(session(), List.of(turn, model), events, "t1");

        assertThat(view.getTree()).contains("thinking=" + "t".repeat(TraceTreeConstants.MAX_NARRATIVE_CHARS) + "...");
        assertThat(view.getTree()).doesNotContain("t".repeat(TraceTreeConstants.MAX_NARRATIVE_CHARS + 1));
    }

    private AgentSession session() {
        AgentSession session = new AgentSession();
        session.setSessionId("s1");
        session.setAgentName("Demo");
        session.setUserId("u1");
        return session;
    }

    private Span span(String spanId, String parentSpanId, SpanTypeEnum type, String attributes,
                      Instant startedAt, Instant endedAt) {
        Span span = new Span();
        span.setSpanId(spanId);
        span.setParentSpanId(parentSpanId);
        span.setSessionId("s1");
        span.setSpanType(type);
        span.setAttributes(attributes);
        span.setStartedAt(startedAt);
        span.setEndedAt(endedAt);
        return span;
    }

    private EventRecord event(String eventId, String spanId, EventTypeEnum type, String payload, Instant occurredAt) {
        EventRecord event = new EventRecord();
        event.setEventId(eventId);
        event.setSpanId(spanId);
        event.setEventType(type);
        event.setPayload(payload);
        event.setOccurredAt(occurredAt);
        return event;
    }
}
