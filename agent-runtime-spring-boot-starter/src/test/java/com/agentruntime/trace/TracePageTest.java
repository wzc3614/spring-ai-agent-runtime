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
package com.agentruntime.trace;

import com.agentruntime.api.domain.AgentSession;
import com.agentruntime.api.domain.EventRecord;
import com.agentruntime.api.domain.Span;
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.SpanTypeEnum;
import com.agentruntime.api.repository.EventRepository;
import com.agentruntime.api.repository.SessionRepository;
import com.agentruntime.api.repository.SpanRepository;
import com.agentruntime.observability.trace.TraceTreeRenderer;
import com.agentruntime.observability.trace.TraceTreeView;
import com.agentruntime.observability.trace.TraceTurnRef;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 页面转义，以及查询服务按 span 取事件。
 */
class TracePageTest {

    @Test
    void escapesTreeAndLeavesSelectedTurnUnlinked() {
        TraceTreeView view = new TraceTreeView();
        view.setTree("tool <script>alert(1)</script>");
        view.setNotice("turn not found");
        view.setSelectedTurnId("t2");
        TraceTurnRef first = turn("t1", 1);
        TraceTurnRef second = turn("t2", 2);
        view.setTurns(List.of(first, second));

        String page = TracePage.render("a&b\"<c>", view);

        assertThat(page).contains("value=\"a&amp;b&quot;&lt;c&gt;\"");
        assertThat(page).contains("tool &lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(page).doesNotContain("<script>");
        assertThat(page).contains("<span class=\"current\">turn 2</span>");
        assertThat(page).contains("sessionId=a%26b%22%3Cc%3E");
        assertThat(page).contains("turn=t1");
        assertThat(page).doesNotContain("turn=t2");
        assertThat(page).contains("turn not found");
    }

    @Test
    void colorsNodeTitlesAndLeavesPayloadTextPlain() {
        TraceTreeView view = new TraceTreeView();
        view.setTree("""
                └── MODEL_CALL #1
                    │
                    ├── SKILL
                    │   └── TOOL_CALL
                    note TOOL_CALL inside text
                """);

        String page = TracePage.render("s1", view);

        assertThat(page).contains("<span class=\"model\">MODEL_CALL</span> #1");
        assertThat(page).contains("<span class=\"skill\">SKILL</span>");
        assertThat(page).contains("<span class=\"tool\">TOOL_CALL</span>");
        assertThat(page).contains("note TOOL_CALL inside text");
        assertThat(page).doesNotContain("note <span");
    }

    @Test
    void loadsOneEventListPerSpan() {
        SessionRepository sessions = mock(SessionRepository.class);
        SpanRepository spans = mock(SpanRepository.class);
        EventRepository events = mock(EventRepository.class);
        AgentSession session = new AgentSession();
        session.setSessionId("s1");
        session.setAgentName("Demo");
        when(sessions.findById("s1")).thenReturn(Optional.of(session));
        Span turn = span("t1", SpanTypeEnum.TURN);
        Span model = span("m1", SpanTypeEnum.MODEL_CALL);
        model.setParentSpanId("t1");
        when(spans.findBySession("s1")).thenReturn(List.of(turn, model));
        EventRecord error = new EventRecord();
        error.setEventId("e1");
        error.setSpanId("m1");
        error.setEventType(EventTypeEnum.ERROR);
        error.setPayload("boom");
        error.setOccurredAt(Instant.parse("2026-10-25T00:00:01Z"));
        when(events.findBySpan("t1")).thenReturn(List.of());
        when(events.findBySpan("m1")).thenReturn(List.of(error));

        TraceTreeService service = new TraceTreeService(sessions, spans, events,
                new TraceTreeRenderer(new ObjectMapper()));
        TraceTreeView view = service.load("s1", null);

        assertThat(view.getSelectedTurnId()).isEqualTo("t1");
        assertThat(view.getTree()).contains("error=boom");
        verify(events).findBySpan("t1");
        verify(events).findBySpan("m1");

        service.load("  ", null);
        verify(sessions, never()).findById("  ");
    }

    private TraceTurnRef turn(String spanId, int index) {
        TraceTurnRef turn = new TraceTurnRef();
        turn.setSpanId(spanId);
        turn.setIndex(index);
        return turn;
    }

    private Span span(String spanId, SpanTypeEnum type) {
        Span span = new Span();
        span.setSpanId(spanId);
        span.setSessionId("s1");
        span.setSpanType(type);
        span.setStartedAt(Instant.parse("2026-10-25T00:00:00Z"));
        span.setEndedAt(Instant.parse("2026-10-25T00:00:01Z"));
        return span;
    }
}
