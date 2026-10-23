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
package com.agentruntime.memory.persistence.jdbc;

import com.agentruntime.api.domain.SummarySnapshot;
import com.agentruntime.api.enums.SummaryStatusEnum;
import com.agentruntime.api.repository.SummarySnapshotRepository;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/**
 * agent_summary_snapshot 的 JDBC 实现。save 始终插入新版本，version 由调用方或 DB 递增。
 * sections 列存通用内容 JSON（由 memory 层负责与 SummaryContent 互转），
 * summarized_up_to_seq 标识已摘要覆盖的 message seq 边界。
 */
public class JdbcSummarySnapshotRepository implements SummarySnapshotRepository {

    private static final String SQL_FIND_LATEST =
            "SELECT summary_id, session_id, version, sections, summarized_up_to_seq, status, error_message, updated_at "
                    + "FROM agent_summary_snapshot WHERE session_id = ? ORDER BY version DESC LIMIT 1";

    private static final String SQL_FIND_LATEST_USABLE =
            "SELECT summary_id, session_id, version, sections, summarized_up_to_seq, status, error_message, updated_at "
                    + "FROM agent_summary_snapshot WHERE session_id = ? AND status <> ? "
                    + "ORDER BY version DESC LIMIT 1";

    private static final String SQL_INSERT =
            "INSERT INTO agent_summary_snapshot (summary_id, session_id, version, sections, summarized_up_to_seq, status, error_message, updated_at) "
                    + "VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?)";

    private final JdbcTemplate jdbcTemplate;

    public JdbcSummarySnapshotRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<SummarySnapshot> findLatest(String sessionId) {
        return jdbcTemplate.query(SQL_FIND_LATEST, AgentRowMappers.SUMMARY_SNAPSHOT, sessionId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<SummarySnapshot> findLatestUsable(String sessionId) {
        return jdbcTemplate.query(SQL_FIND_LATEST_USABLE, AgentRowMappers.SUMMARY_SNAPSHOT,
                        sessionId, SummaryStatusEnum.FAILED.getCode())
                .stream()
                .findFirst();
    }

    @Override
    public SummarySnapshot save(SummarySnapshot snapshot) {
        jdbcTemplate.update(SQL_INSERT,
                snapshot.getSummaryId(),
                snapshot.getSessionId(),
                snapshot.getVersion(),
                snapshot.getSections(),
                snapshot.getSummarizedUpToSeq(),
                snapshot.getStatus(),
                snapshot.getErrorMessage(),
                JdbcUtils.toTimestamp(snapshot.getUpdatedAt()));
        return snapshot;
    }
}
