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

import com.agentruntime.api.domain.Span;
import com.agentruntime.api.repository.SpanRepository;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * agent_runtime_obs.span 的 JDBC 实现。
 */
public class JdbcSpanRepository implements SpanRepository {

    private static final String SQL_INSERT =
            "INSERT INTO agent_runtime_obs.span "
                    + "(span_id, parent_span_id, session_id, span_type, started_at, ended_at, attributes) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (span_id) DO NOTHING";

    private static final String SQL_FIND_BY_ID =
            "SELECT span_id, parent_span_id, session_id, span_type, started_at, ended_at, attributes "
                    + "FROM agent_runtime_obs.span WHERE span_id = ?";

    private static final String SQL_FIND_BY_SESSION =
            "SELECT span_id, parent_span_id, session_id, span_type, started_at, ended_at, attributes "
                    + "FROM agent_runtime_obs.span WHERE session_id = ?";

    private static final String SQL_FIND_BY_PARENT =
            "SELECT span_id, parent_span_id, session_id, span_type, started_at, ended_at, attributes "
                    + "FROM agent_runtime_obs.span WHERE parent_span_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public JdbcSpanRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Span save(Span span) {
        jdbcTemplate.update(SQL_INSERT,
                span.getSpanId(),
                span.getParentSpanId(),
                span.getSessionId(),
                span.getSpanType().getCode(),
                toTimestamp(span.getStartedAt()),
                toTimestamp(span.getEndedAt()),
                span.getAttributes());
        return span;
    }

    @Override
    public Optional<Span> findById(String spanId) {
        return jdbcTemplate.query(SQL_FIND_BY_ID, ObservabilityRowMappers.SPAN, spanId)
                .stream()
                .findFirst();
    }

    @Override
    public List<Span> findBySession(String sessionId) {
        return jdbcTemplate.query(SQL_FIND_BY_SESSION, ObservabilityRowMappers.SPAN, sessionId);
    }

    @Override
    public List<Span> findByParent(String parentSpanId) {
        return jdbcTemplate.query(SQL_FIND_BY_PARENT, ObservabilityRowMappers.SPAN, parentSpanId);
    }

    private static java.sql.Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : java.sql.Timestamp.from(instant);
    }
}
