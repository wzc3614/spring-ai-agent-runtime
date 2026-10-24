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

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/**
 * agent_summary_snapshot 的 JDBC 实现。save 在数据库里取 MAX(version)+1，
 * 唯一约束冲突时重试。sections 列存通用内容 JSON，summarized_up_to_seq 是摘要边界。
 */
public class JdbcSummarySnapshotRepository implements SummarySnapshotRepository {

    /** 并发插入撞上唯一约束时的重试次数 */
    private static final int VERSION_INSERT_ATTEMPTS = 3;

    private static final String SQL_FIND_LATEST =
            "SELECT summary_id, session_id, version, sections, summarized_up_to_seq, status, error_message, updated_at "
                    + "FROM agent_summary_snapshot WHERE session_id = ? ORDER BY version DESC LIMIT 1";

    private static final String SQL_FIND_LATEST_USABLE =
            "SELECT summary_id, session_id, version, sections, summarized_up_to_seq, status, error_message, updated_at "
                    + "FROM agent_summary_snapshot WHERE session_id = ? AND status <> ? "
                    + "ORDER BY version DESC LIMIT 1";

    /**
     * version 用标量子查询计算。空会话时 MAX 为 NULL，COALESCE 得到 1。
     * 不能写成 FROM 本表的 INSERT SELECT，否则没有旧行时插入 0 行。
     */
    private static final String SQL_INSERT =
            "INSERT INTO agent_summary_snapshot "
                    + "(summary_id, session_id, version, sections, summarized_up_to_seq, status, error_message, updated_at) "
                    + "VALUES (?, ?, (SELECT COALESCE(MAX(version), 0) + 1 FROM agent_summary_snapshot WHERE session_id = ?), "
                    + "?::jsonb, ?, ?, ?, ?) RETURNING version";

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
        DuplicateKeyException lastConflict = null;
        for (int attempt = 1; attempt <= VERSION_INSERT_ATTEMPTS; attempt++) {
            try {
                Number version = jdbcTemplate.queryForObject(SQL_INSERT, Number.class,
                        snapshot.getSummaryId(),
                        snapshot.getSessionId(),
                        snapshot.getSessionId(),
                        snapshot.getSections(),
                        snapshot.getSummarizedUpToSeq(),
                        snapshot.getStatus(),
                        snapshot.getErrorMessage(),
                        JdbcUtils.toTimestamp(snapshot.getUpdatedAt()));
                if (version == null) {
                    throw new IllegalStateException("summary version missing, sessionId=" + snapshot.getSessionId());
                }
                snapshot.setVersion(version.intValue());
                return snapshot;
            } catch (DuplicateKeyException e) {
                lastConflict = e;
            }
        }
        throw lastConflict == null
                ? new IllegalStateException("summary insert failed, sessionId=" + snapshot.getSessionId())
                : lastConflict;
    }
}
