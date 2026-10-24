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

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.enums.MessageRoleEnum;
import com.agentruntime.api.enums.ToolCompactionStrategyEnum;
import com.agentruntime.api.repository.MessageRepository;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * agent_message 的 JDBC 实现。写入只追加，ON CONFLICT(session_id, seq) DO NOTHING 保证幂等；
 * 读取按 seq 升序返回。
 */
public class JdbcMessageRepository implements MessageRepository {

    private static final String JSON_NULL = "null";

    private static final String MESSAGE_COLUMNS =
            "message_id, session_id, seq, role, content, compacted, evidence_id, payload, compaction_class, created_at";

    private static final String SQL_INSERT =
            "INSERT INTO agent_message (message_id, session_id, seq, role, content, compacted, evidence_id, "
                    + "payload, compaction_class, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?) "
                    + "ON CONFLICT (session_id, seq) DO NOTHING";

    private static final String SQL_FIND_BY_SESSION =
            "SELECT " + MESSAGE_COLUMNS + " FROM agent_message WHERE session_id = ? ORDER BY seq ASC";

    private static final String SQL_FIND_BY_SESSION_FROM =
            "SELECT " + MESSAGE_COLUMNS
                    + " FROM agent_message WHERE session_id = ? AND seq > ? ORDER BY seq ASC";

    private static final String SQL_FIND_LATEST =
            "SELECT " + MESSAGE_COLUMNS
                    + " FROM agent_message WHERE session_id = ? ORDER BY seq DESC LIMIT 1";

    private static final String SQL_FIND_BY_SESSION_AND_SEQ =
            "SELECT " + MESSAGE_COLUMNS + " FROM agent_message WHERE session_id = ? AND seq = ?";

    private static final String SQL_MARK_COMPACTED =
            "UPDATE agent_message SET compacted = true, evidence_id = ? WHERE message_id = ?";

    private static final String SQL_UPDATE_TOOL_VIEW =
            "UPDATE agent_message SET content = ?, payload = ?::jsonb, compacted = ? WHERE message_id = ?";

    private static final String SQL_FIND_RETAINED_UP_TO =
            "SELECT " + MESSAGE_COLUMNS
                    + " FROM agent_message WHERE session_id = ? AND seq <= ? AND compaction_class = ? "
                    + "ORDER BY seq ASC";

    private static final String SQL_FIND_UNCOMPACTED_TOOLS =
            "SELECT " + MESSAGE_COLUMNS
                    + " FROM agent_message WHERE session_id = ? AND role = ? AND compacted = false "
                    + "ORDER BY seq ASC";

    private static final String SQL_FIND_USER_SEQS =
            "SELECT seq FROM agent_message WHERE session_id = ? AND role = ? ORDER BY seq ASC";

    private static final String SQL_DELETE_BY_SESSION =
            "DELETE FROM agent_message WHERE session_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public JdbcMessageRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int append(AgentMessage message) {
        return jdbcTemplate.update(SQL_INSERT,
                message.getMessageId(),
                message.getSessionId(),
                message.getSeq(),
                message.getRole().getCode(),
                message.getContent(),
                message.isCompacted(),
                message.getEvidenceId(),
                jsonb(message.getPayload()),
                message.getCompactionClass(),
                JdbcUtils.toTimestamp(message.getCreatedAt()));
    }

    @Override
    public Optional<AgentMessage> findBySessionAndSeq(String sessionId, long seq) {
        return jdbcTemplate.query(SQL_FIND_BY_SESSION_AND_SEQ, AgentRowMappers.MESSAGE, sessionId, seq)
                .stream()
                .findFirst();
    }

    @Override
    public List<AgentMessage> findBySession(String sessionId) {
        return jdbcTemplate.query(SQL_FIND_BY_SESSION, AgentRowMappers.MESSAGE, sessionId);
    }

    @Override
    public List<AgentMessage> findBySession(String sessionId, long fromSeq) {
        return jdbcTemplate.query(SQL_FIND_BY_SESSION_FROM, AgentRowMappers.MESSAGE, sessionId, fromSeq);
    }

    @Override
    public Optional<AgentMessage> findLatest(String sessionId) {
        return jdbcTemplate.query(SQL_FIND_LATEST, AgentRowMappers.MESSAGE, sessionId)
                .stream()
                .findFirst();
    }

    @Override
    public int markCompacted(String messageId, String evidenceId) {
        return jdbcTemplate.update(SQL_MARK_COMPACTED, evidenceId, messageId);
    }

    @Override
    public int updateToolView(String messageId, String content, String payload, boolean compacted) {
        return jdbcTemplate.update(SQL_UPDATE_TOOL_VIEW, content, jsonb(payload), compacted, messageId);
    }

    /** JSON null literal，避免 SQL NULL 无法匹配 ?::jsonb */
    private static String jsonb(String payload) {
        return payload == null ? JSON_NULL : payload;
    }

    @Override
    public List<AgentMessage> findRetainedUpTo(String sessionId, long upToSeq) {
        return jdbcTemplate.query(SQL_FIND_RETAINED_UP_TO, AgentRowMappers.MESSAGE,
                sessionId, upToSeq, ToolCompactionStrategyEnum.RETAIN.getCode());
    }

    @Override
    public List<AgentMessage> findUncompactedToolMessages(String sessionId) {
        return jdbcTemplate.query(SQL_FIND_UNCOMPACTED_TOOLS, AgentRowMappers.MESSAGE,
                sessionId, MessageRoleEnum.TOOL.getCode());
    }

    @Override
    public List<Long> findUserSeqs(String sessionId) {
        return jdbcTemplate.query(SQL_FIND_USER_SEQS,
                (rs, rowNum) -> rs.getLong("seq"), sessionId, MessageRoleEnum.USER.getCode());
    }

    @Override
    public int deleteBySession(String sessionId) {
        return jdbcTemplate.update(SQL_DELETE_BY_SESSION, sessionId);
    }
}
