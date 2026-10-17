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
package com.agentruntime.api.repository;

import com.agentruntime.api.domain.ToolCall;
import com.agentruntime.api.enums.ToolCallStatusEnum;

import java.util.List;
import java.util.Optional;

/**
 * 工具调用仓储，对应 agent_tool_call 表。
 */
public interface ToolCallRepository {

    /**
     * 保存工具调用。
     *
     * @param toolCall 工具调用实体
     * @return 保存后的实体
     */
    ToolCall save(ToolCall toolCall);

    /**
     * 根据工具调用 ID 查询。
     *
     * @param toolCallId 工具调用 ID
     * @return 工具调用实体
     */
    Optional<ToolCall> findById(String toolCallId);

    /**
     * 查询某消息发起的全部工具调用。
     *
     * @param messageId 消息 ID
     * @return 工具调用列表
     */
    List<ToolCall> findByMessage(String messageId);

    /**
     * 查询会话内处于未终结状态（PENDING / AWAITING_APPROVAL）的工具调用，
     * 用于悬空调用修复与审批恢复。
     *
     * @param sessionId 会话 ID
     * @return 工具调用列表
     */
    List<ToolCall> findPendingBySession(String sessionId);

    /**
     * 更新工具调用状态（条件更新，避免覆盖）。
     *
     * @param toolCallId 工具调用 ID
     * @param status     目标状态
     * @return 受影响行数
     */
    int updateStatus(String toolCallId, ToolCallStatusEnum status);

    /**
     * 更新工具调用结果（条件更新）。
     *
     * @param toolCallId 工具调用 ID
     * @param result     结果（JSON 字符串）
     * @return 受影响行数
     */
    int updateResult(String toolCallId, String result);
}
