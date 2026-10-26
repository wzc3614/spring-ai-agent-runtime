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
import com.agentruntime.api.domain.AgentSession;
import com.agentruntime.api.domain.SummarySnapshot;
import com.agentruntime.api.domain.ToolCall;
import com.agentruntime.api.enums.FailureCategoryEnum;
import com.agentruntime.api.enums.MessageRoleEnum;
import com.agentruntime.api.enums.RiskLevelEnum;
import com.agentruntime.api.enums.SessionStatusEnum;
import com.agentruntime.api.enums.ToolCallStatusEnum;

import org.springframework.jdbc.core.RowMapper;

/**
 * agent_* 表的 RowMapper 集合。JSONB 列用 getString 读取为原始 JSON 字符串。
 */
public final class AgentRowMappers {

    /** agent_session 行映射 */
    public static final RowMapper<AgentSession> SESSION =
            (rs, rowNum) -> {
                AgentSession session = new AgentSession();
                session.setSessionId(rs.getString("session_id"));
                session.setAgentName(rs.getString("agent_name"));
                session.setUserId(rs.getString("user_id"));
                session.setStatus(SessionStatusEnum.of(rs.getString("status")));
                session.setVersion(rs.getLong("version"));
                session.setCreatedAt(JdbcUtils.toInstant(rs.getTimestamp("created_at")));
                session.setLastActiveAt(JdbcUtils.toInstant(rs.getTimestamp("last_active_at")));
                return session;
            };

    /** agent_message 行映射 */
    public static final RowMapper<AgentMessage> MESSAGE =
            (rs, rowNum) -> {
                AgentMessage message = new AgentMessage();
                message.setMessageId(rs.getString("message_id"));
                message.setSessionId(rs.getString("session_id"));
                message.setSeq(rs.getLong("seq"));
                message.setRole(MessageRoleEnum.of(rs.getString("role")));
                message.setContent(rs.getString("content"));
                message.setCompacted(rs.getBoolean("compacted"));
                message.setEvidenceId(rs.getString("evidence_id"));
                message.setPayload(rs.getString("payload"));
                message.setCompactionClass(rs.getString("compaction_class"));
                message.setTurnId(rs.getString("turn_id"));
                message.setCreatedAt(JdbcUtils.toInstant(rs.getTimestamp("created_at")));
                return message;
            };

    /** agent_summary_snapshot 行映射 */
    public static final RowMapper<SummarySnapshot> SUMMARY_SNAPSHOT =
            (rs, rowNum) -> {
                SummarySnapshot snapshot = new SummarySnapshot();
                snapshot.setSummaryId(rs.getString("summary_id"));
                snapshot.setSessionId(rs.getString("session_id"));
                snapshot.setVersion(rs.getInt("version"));
                snapshot.setSections(rs.getString("sections"));
                snapshot.setSummarizedUpToSeq(rs.getLong("summarized_up_to_seq"));
                snapshot.setStatus(rs.getString("status"));
                snapshot.setErrorMessage(rs.getString("error_message"));
                snapshot.setUpdatedAt(JdbcUtils.toInstant(rs.getTimestamp("updated_at")));
                return snapshot;
            };

    /** agent_tool_call 行映射 */
    public static final RowMapper<ToolCall> TOOL_CALL =
            (rs, rowNum) -> {
                ToolCall toolCall = new ToolCall();
                toolCall.setToolCallId(rs.getString("tool_call_id"));
                toolCall.setMessageId(rs.getString("message_id"));
                toolCall.setToolName(rs.getString("tool_name"));
                toolCall.setArguments(rs.getString("arguments"));
                toolCall.setResult(rs.getString("result"));
                toolCall.setStatus(ToolCallStatusEnum.of(rs.getString("status")));
                toolCall.setIdempotent(rs.getBoolean("idempotent"));
                String riskLevel = rs.getString("risk_level");
                toolCall.setRiskLevel(riskLevel == null ? null : RiskLevelEnum.of(riskLevel));
                toolCall.setRetryCount(rs.getInt("retry_count"));
                String failureCategory = rs.getString("failure_category");
                toolCall.setFailureCategory(failureCategory == null ? null : FailureCategoryEnum.of(failureCategory));
                toolCall.setCreatedAt(JdbcUtils.toInstant(rs.getTimestamp("created_at")));
                return toolCall;
            };

    private AgentRowMappers() {
    }
}
