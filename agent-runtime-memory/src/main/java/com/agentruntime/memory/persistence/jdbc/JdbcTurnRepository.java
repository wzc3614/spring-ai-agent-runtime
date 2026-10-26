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

import com.agentruntime.api.domain.TurnRecord;
import com.agentruntime.api.enums.TurnStatusEnum;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;
import java.util.Optional;

/**
 * agent_turn 的 JDBC 镜像。租约不在这张表里。
 */
public class JdbcTurnRepository {

    private static final String SQL_INSERT =
            "INSERT INTO agent_turn (turn_id, session_id, user_id, agent_name, query, status, "
                    + "error_code, final_answer, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private static final String SQL_UPDATE =
            "UPDATE agent_turn SET status = ?, error_code = ?, final_answer = ?, updated_at = ? "
                    + "WHERE turn_id = ?";

    private static final String SQL_FIND =
            "SELECT turn_id, session_id, user_id, agent_name, query, status, error_code, final_answer, "
                    + "created_at, updated_at FROM agent_turn WHERE turn_id = ?";

    private static final String SQL_LIST_OPEN =
            "SELECT turn_id, session_id, user_id, agent_name, query, status, error_code, final_answer, "
                    + "created_at, updated_at FROM agent_turn WHERE agent_name = ? AND status IN (?, ?)";

    private static final RowMapper<TurnRecord> ROW_MAPPER = (rs, rowNum) -> {
        TurnRecord turn = new TurnRecord();
        turn.setTurnId(rs.getString("turn_id"));
        turn.setSessionId(rs.getString("session_id"));
        turn.setUserId(rs.getString("user_id"));
        turn.setAgentName(rs.getString("agent_name"));
        turn.setQuery(rs.getString("query"));
        turn.setStatus(TurnStatusEnum.of(rs.getString("status")));
        turn.setErrorCode(rs.getString("error_code"));
        turn.setFinalAnswer(rs.getString("final_answer"));
        turn.setCreatedAt(JdbcUtils.toInstant(rs.getTimestamp("created_at")));
        turn.setUpdatedAt(JdbcUtils.toInstant(rs.getTimestamp("updated_at")));
        return turn;
    };

    private final JdbcTemplate jdbcTemplate;

    public JdbcTurnRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 插入一轮。调用方保证 turnId 尚未存在。
     *
     * @param turn 轮次
     */
    public void insert(TurnRecord turn) {
        jdbcTemplate.update(SQL_INSERT,
                turn.getTurnId(),
                turn.getSessionId(),
                turn.getUserId(),
                turn.getAgentName(),
                turn.getQuery(),
                turn.getStatus().getCode(),
                turn.getErrorCode(),
                turn.getFinalAnswer(),
                JdbcUtils.toTimestamp(turn.getCreatedAt()),
                JdbcUtils.toTimestamp(turn.getUpdatedAt()));
    }

    /**
     * 回写终态或 RUNNING。租约列不在表里。
     *
     * @param turn 轮次
     */
    public void update(TurnRecord turn) {
        jdbcTemplate.update(SQL_UPDATE,
                turn.getStatus().getCode(),
                turn.getErrorCode(),
                turn.getFinalAnswer(),
                JdbcUtils.toTimestamp(turn.getUpdatedAt()),
                turn.getTurnId());
    }

    /**
     * @param turnId 轮次
     * @return 镜像行
     */
    public Optional<TurnRecord> find(String turnId) {
        return jdbcTemplate.query(SQL_FIND, ROW_MAPPER, turnId).stream().findFirst();
    }

    /**
     * @param agentName agent 名称
     * @return ACCEPTED 与 RUNNING
     */
    public List<TurnRecord> listOpen(String agentName) {
        return jdbcTemplate.query(SQL_LIST_OPEN, ROW_MAPPER, agentName,
                TurnStatusEnum.ACCEPTED.getCode(), TurnStatusEnum.RUNNING.getCode());
    }
}
