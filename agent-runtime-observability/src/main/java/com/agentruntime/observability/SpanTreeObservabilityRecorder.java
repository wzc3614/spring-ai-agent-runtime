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
package com.agentruntime.observability;

import com.agentruntime.api.domain.EventRecord;
import com.agentruntime.api.domain.Span;
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.SpanTypeEnum;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.api.observability.ObservabilitySink;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Span/Event 树记录器默认实现。startSpan 建 Span 入 pending；recordEvent 把事件缓冲到对应 span；
 * endSpan 设 endedAt，先 writeSpan 再 writeEvent（保证 event 的 span FK 已存在）。
 * Span/Event id 用 UUID。
 */
public class SpanTreeObservabilityRecorder implements ObservabilityRecorder {

    private final List<ObservabilitySink> sinks;

    private final ConcurrentMap<String, Span> pending = new ConcurrentHashMap<>();

    /** 每个 span 缓冲的事件，待 span 结束时统一写出（保证 FK） */
    private final ConcurrentMap<String, List<EventRecord>> pendingEvents = new ConcurrentHashMap<>();

    public SpanTreeObservabilityRecorder(List<ObservabilitySink> sinks) {
        this.sinks = sinks;
    }

    @Override
    public Span startSpan(SpanTypeEnum type, String parentSpanId, String sessionId) {
        Span span = new Span();
        span.setSpanId(UUID.randomUUID().toString());
        span.setParentSpanId(parentSpanId);
        span.setSessionId(sessionId);
        span.setSpanType(type);
        span.setStartedAt(Instant.now());
        pending.put(span.getSpanId(), span);
        return span;
    }

    @Override
    public void endSpan(String spanId) {
        Span span = pending.remove(spanId);
        if (span == null) {
            return;
        }
        span.setEndedAt(Instant.now());
        // 先写 span，再写其缓冲事件，保证 event 的 span FK 已存在
        for (ObservabilitySink sink : sinks) {
            sink.writeSpan(span);
        }
        List<EventRecord> events = pendingEvents.remove(spanId);
        if (events != null) {
            for (EventRecord event : events) {
                for (ObservabilitySink sink : sinks) {
                    sink.writeEvent(event);
                }
            }
        }
    }

    @Override
    public void recordEvent(String spanId, EventTypeEnum type, String payload) {
        EventRecord event = new EventRecord();
        event.setEventId(UUID.randomUUID().toString());
        event.setSpanId(spanId);
        event.setEventType(type);
        event.setPayload(payload);
        event.setOccurredAt(Instant.now());
        // 缓冲到对应 span，待 endSpan 时统一写出
        pendingEvents.computeIfAbsent(spanId, k -> new ArrayList<>()).add(event);
    }
}
