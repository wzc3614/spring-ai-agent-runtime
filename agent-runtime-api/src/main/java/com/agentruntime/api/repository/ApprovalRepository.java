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

import com.agentruntime.api.domain.ApprovalRecord;
import com.agentruntime.api.enums.ApprovalStatusEnum;

import java.util.List;
import java.util.Optional;

/**
 * 审批记录仓储，对应 agent_approval_record 表。
 */
public interface ApprovalRepository {

    /**
     * 保存审批记录。
     *
     * @param record 审批记录
     * @return 保存后的实体
     */
    ApprovalRecord save(ApprovalRecord record);

    /**
     * 根据审批 ID 查询。
     *
     * @param approvalId 审批 ID
     * @return 审批记录
     */
    Optional<ApprovalRecord> findById(String approvalId);

    /**
     * 根据工具调用 ID 查询审批记录。
     *
     * @param toolCallId 工具调用 ID
     * @return 审批记录
     */
    Optional<ApprovalRecord> findByToolCall(String toolCallId);

    /**
     * 查询全部待审批记录。
     *
     * @return 待审批列表
     */
    List<ApprovalRecord> findPending();

    /**
     * 写入审批决策。
     *
     * @param approvalId 审批 ID
     * @param status     决策状态
     * @param decidedBy  决策人
     * @param reason     决策原因
     * @return 受影响行数
     */
    int updateDecision(String approvalId, ApprovalStatusEnum status, String decidedBy, String reason);
}
