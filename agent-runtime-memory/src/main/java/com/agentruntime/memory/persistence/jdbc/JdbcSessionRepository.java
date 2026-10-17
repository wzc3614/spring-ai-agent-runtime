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

import com.agentruntime.api.domain.AgentSession;
import com.agentruntime.api.enums.SessionStatusEnum;
import com.agentruntime.api.exception.OptimisticLockException;
import com.agentruntime.api.repository.SessionRepository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * agent_session 的 JDBC 实现。save 用 version 乐观锁：version=0 视为新增走 INSERT，
 * 否则走带 version 条件的 UPDATE，0 行命中即抛 {@link OptimisticLockException}。
 */
public class JdbcSessionRepository implements SessionRepository {

    private static final String SQL_FIND_BY_ID =
            "SELECT session_id, agent_name, user_id, status, version, created_at, last_active_at "
                    + "FROM agent_session WHERE session_id = ?";

    private static final String SQL_UPDATE_WITH_VERSION =
            "UPDATE agent_session SET agent_name = ?, user_id = ?, status = ?, version = version + 1, "
                    + "last_active_at = ? WHERE session_id = ? AND version = ?";

    private static final String SQL_UPDATE_STATUS =
            "UPDATE agent_session SET status = ? WHERE session_id = ?";

    private static final String SQL_TOUCH =
            "UPDATE agent_session SET last_active_at = now() WHERE session_id = ?";

    private final JdbcTemplate jdbcTemplate;

    private final SimpleJdbcInsert sessionInsert;

    public JdbcSessionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.sessionInsert = new SimpleJdbcInsert(jdbcTemplate)
                .withTableName("agent_session")
                .usingColumns("session_id", "agent_name", "user_id", "status", "version", "created_at", "last_active_at");
    }

    @Override
    public Optional<AgentSession> findById(String sessionId) {
        return jdbcTemplate.query(SQL_FIND_BY_ID, AgentRowMappers.SESSION, sessionId)
                .stream()
                .findFirst();
    }

    @Override
    public AgentSession save(AgentSession session) {
        if (session.getVersion() == 0L) {
            return insert(session);
        }
        return update(session);
    }

    private AgentSession insert(AgentSession session) {
        Map<String, Object> params = new HashMap<>(8);
        params.put("session_id", session.getSessionId());
        params.put("agent_name", session.getAgentName());
        params.put("user_id", session.getUserId());
        params.put("status", session.getStatus().getCode());
        params.put("version", 1L);
        params.put("created_at", JdbcUtils.toTimestamp(session.getCreatedAt()));
        params.put("last_active_at", JdbcUtils.toTimestamp(session.getLastActiveAt()));
        sessionInsert.execute(params);
        session.setVersion(1L);
        return session;
    }

    private AgentSession update(AgentSession session) {
        int rows = jdbcTemplate.update(SQL_UPDATE_WITH_VERSION,
                session.getAgentName(),
                session.getUserId(),
                session.getStatus().getCode(),
                JdbcUtils.toTimestamp(session.getLastActiveAt()),
                session.getSessionId(),
                session.getVersion());
        if (rows == 0) {
            throw new OptimisticLockException("session version conflict, sessionId=" + session.getSessionId());
        }
        session.setVersion(session.getVersion() + 1);
        return session;
    }

    @Override
    public int updateStatus(String sessionId, SessionStatusEnum status) {
        return jdbcTemplate.update(SQL_UPDATE_STATUS, status.getCode(), sessionId);
    }

    @Override
    public int touch(String sessionId) {
        return jdbcTemplate.update(SQL_TOUCH, sessionId);
    }
}
