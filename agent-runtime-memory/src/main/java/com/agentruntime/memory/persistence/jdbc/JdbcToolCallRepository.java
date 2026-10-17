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

import com.agentruntime.api.domain.ToolCall;
import com.agentruntime.api.enums.ToolCallStatusEnum;
import com.agentruntime.api.repository.ToolCallRepository;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;

/**
 * agent_tool_call 的 JDBC 实现。状态/结果更新走条件 SQL，避免覆盖。
 */
public class JdbcToolCallRepository implements ToolCallRepository {

    private static final String SQL_INSERT =
            "INSERT INTO agent_tool_call "
                    + "(tool_call_id, message_id, tool_name, arguments, result, status, idempotent, "
                    + "risk_level, retry_count, failure_category, created_at) "
                    + "VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (tool_call_id) DO NOTHING";

    private static final String SQL_FIND_BY_ID =
            "SELECT tool_call_id, message_id, tool_name, arguments, result, status, idempotent, "
                    + "risk_level, retry_count, failure_category, created_at "
                    + "FROM agent_tool_call WHERE tool_call_id = ?";

    private static final String SQL_FIND_BY_MESSAGE =
            "SELECT tool_call_id, message_id, tool_name, arguments, result, status, idempotent, "
                    + "risk_level, retry_count, failure_category, created_at "
                    + "FROM agent_tool_call WHERE message_id = ?";

    private static final String SQL_FIND_PENDING =
            "SELECT tc.tool_call_id, tc.message_id, tc.tool_name, tc.arguments, tc.result, tc.status, "
                    + "tc.idempotent, tc.risk_level, tc.retry_count, tc.failure_category, tc.created_at "
                    + "FROM agent_tool_call tc "
                    + "JOIN agent_message m ON m.message_id = tc.message_id "
                    + "WHERE m.session_id = ? AND tc.status IN ('PENDING', 'AWAITING_APPROVAL')";

    private static final String SQL_UPDATE_STATUS =
            "UPDATE agent_tool_call SET status = ? WHERE tool_call_id = ?";

    private static final String SQL_UPDATE_RESULT =
            "UPDATE agent_tool_call SET result = ?::jsonb WHERE tool_call_id = ?";

    private final JdbcTemplate jdbcTemplate;

    public JdbcToolCallRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ToolCall save(ToolCall toolCall) {
        jdbcTemplate.update(SQL_INSERT,
                toolCall.getToolCallId(),
                toolCall.getMessageId(),
                toolCall.getToolName(),
                toolCall.getArguments(),
                toolCall.getResult(),
                toolCall.getStatus().getCode(),
                toolCall.isIdempotent(),
                toolCall.getRiskLevel() == null ? null : toolCall.getRiskLevel().getCode(),
                toolCall.getRetryCount(),
                toolCall.getFailureCategory() == null ? null : toolCall.getFailureCategory().getCode(),
                JdbcUtils.toTimestamp(toolCall.getCreatedAt()));
        return toolCall;
    }

    @Override
    public Optional<ToolCall> findById(String toolCallId) {
        return jdbcTemplate.query(SQL_FIND_BY_ID, AgentRowMappers.TOOL_CALL, toolCallId)
                .stream()
                .findFirst();
    }

    @Override
    public List<ToolCall> findByMessage(String messageId) {
        return jdbcTemplate.query(SQL_FIND_BY_MESSAGE, AgentRowMappers.TOOL_CALL, messageId);
    }

    @Override
    public List<ToolCall> findPendingBySession(String sessionId) {
        return jdbcTemplate.query(SQL_FIND_PENDING, AgentRowMappers.TOOL_CALL, sessionId);
    }

    @Override
    public int updateStatus(String toolCallId, ToolCallStatusEnum status) {
        return jdbcTemplate.update(SQL_UPDATE_STATUS, status.getCode(), toolCallId);
    }

    @Override
    public int updateResult(String toolCallId, String result) {
        return jdbcTemplate.update(SQL_UPDATE_RESULT, result, toolCallId);
    }
}
