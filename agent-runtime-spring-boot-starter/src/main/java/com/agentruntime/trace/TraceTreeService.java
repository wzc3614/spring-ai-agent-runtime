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
import com.agentruntime.api.repository.EventRepository;
import com.agentruntime.api.repository.SessionRepository;
import com.agentruntime.api.repository.SpanRepository;
import com.agentruntime.observability.trace.TraceTreeRenderer;
import com.agentruntime.observability.trace.TraceTreeView;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 按会话读取已落库的 span / event，交给渲染器画一轮调用树。单会话的 N+1 查询可以接受。
 */
public class TraceTreeService {

    private final SessionRepository sessionRepository;

    private final SpanRepository spanRepository;

    private final EventRepository eventRepository;

    private final TraceTreeRenderer renderer;

    public TraceTreeService(SessionRepository sessionRepository, SpanRepository spanRepository,
                            EventRepository eventRepository, TraceTreeRenderer renderer) {
        this.sessionRepository = sessionRepository;
        this.spanRepository = spanRepository;
        this.eventRepository = eventRepository;
        this.renderer = renderer;
    }

    /**
     * 加载一棵调用树。
     *
     * @param sessionId       会话 ID
     * @param requestedTurnId TURN span id。空白时由渲染器选最近一轮
     * @return 展示结果
     */
    public TraceTreeView load(String sessionId, String requestedTurnId) {
        if (!StringUtils.hasText(sessionId)) {
            return renderer.render(null, List.of(), Map.of(), requestedTurnId);
        }
        AgentSession session = sessionRepository.findById(sessionId).orElse(null);
        List<Span> spans = spanRepository.findBySession(sessionId);
        if (spans == null) {
            spans = List.of();
        }
        Map<String, List<EventRecord>> eventsBySpan = new LinkedHashMap<>();
        for (Span span : spans) {
            if (!StringUtils.hasText(span.getSpanId())) {
                continue;
            }
            eventsBySpan.put(span.getSpanId(), eventRepository.findBySpan(span.getSpanId()));
        }
        return renderer.render(session, spans, eventsBySpan, requestedTurnId);
    }
}
