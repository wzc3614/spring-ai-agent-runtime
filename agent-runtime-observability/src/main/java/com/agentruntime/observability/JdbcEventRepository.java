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
import com.agentruntime.api.repository.EventRepository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

/**
 * agent_runtime_obs.event_record 的 JDBC 实现。
 */
public class JdbcEventRepository implements EventRepository {

    private static final String SQL_INSERT =
            "INSERT INTO agent_runtime_obs.event_record "
                    + "(event_id, span_id, event_type, payload, occurred_at) "
                    + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (event_id) DO NOTHING";

    private static final String SQL_FIND_BY_SPAN =
            "SELECT event_id, span_id, event_type, payload, occurred_at "
                    + "FROM agent_runtime_obs.event_record WHERE span_id = ?";

    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public JdbcEventRepository(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public EventRecord save(EventRecord event) {
        jdbcTemplate.update(SQL_INSERT,
                event.getEventId(),
                event.getSpanId(),
                event.getEventType().getCode(),
                event.getPayload(),
                toTimestamp(event.getOccurredAt()));
        return event;
    }

    @Override
    public List<EventRecord> findBySpan(String spanId) {
        return jdbcTemplate.query(SQL_FIND_BY_SPAN, ObservabilityRowMappers.EVENT, spanId);
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
