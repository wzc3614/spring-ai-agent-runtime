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

import java.sql.Timestamp;
import java.time.Instant;

/**
 * agent_runtime_obs.span / event_record 的 RowMapper 集合。
 */
public final class ObservabilityRowMappers {

    /** span 行映射 */
    public static final RowMapper<Span> SPAN =
            (rs, rowNum) -> {
                Span span = new Span();
                span.setSpanId(rs.getString("span_id"));
                span.setParentSpanId(rs.getString("parent_span_id"));
                span.setSessionId(rs.getString("session_id"));
                span.setSpanType(SpanTypeEnum.of(rs.getString("span_type")));
                span.setStartedAt(toInstant(rs.getTimestamp("started_at")));
                span.setEndedAt(toInstant(rs.getTimestamp("ended_at")));
                span.setAttributes(rs.getString("attributes"));
                return span;
            };

    /** event 行映射 */
    public static final RowMapper<EventRecord> EVENT =
            (rs, rowNum) -> {
                EventRecord event = new EventRecord();
                event.setEventId(rs.getString("event_id"));
                event.setSpanId(rs.getString("span_id"));
                event.setEventType(EventTypeEnum.of(rs.getString("event_type")));
                event.setPayload(rs.getString("payload"));
                event.setOccurredAt(toInstant(rs.getTimestamp("occurred_at")));
                return event;
            };

    private ObservabilityRowMappers() {
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /** RowMapper 类型别名，避免每个引用处写全限定名 */
    public interface RowMapper<T> extends org.springframework.jdbc.core.RowMapper<T> {
    }
}
