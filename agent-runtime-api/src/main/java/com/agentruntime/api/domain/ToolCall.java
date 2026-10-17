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
package com.agentruntime.api.domain;

import com.agentruntime.api.enums.FailureCategoryEnum;
import com.agentruntime.api.enums.RiskLevelEnum;
import com.agentruntime.api.enums.ToolCallStatusEnum;

import java.time.Instant;

/**
 * 工具调用实体，对应 agent_tool_call 表。挂在发起它的 assistant 消息上。
 * idempotent=false 的工具在 AMBIGUOUS 失败下绝不自动重试。
 */
public class ToolCall {

    /** 工具调用唯一 ID */
    private String toolCallId;

    /** 发起该调用的 assistant 消息 ID */
    private String messageId;

    /** 工具名称 */
    private String toolName;

    /** 调用参数（JSON 字符串） */
    private String arguments;

    /** 调用结果（JSON 字符串，可能很大；超阈值时落盘，此处存引用） */
    private String result;

    /** 调用状态 */
    private ToolCallStatusEnum status;

    /** 是否幂等 */
    private boolean idempotent;

    /** 风险等级 */
    private RiskLevelEnum riskLevel;

    /** 已重试次数 */
    private int retryCount;

    /** 失败分类 */
    private FailureCategoryEnum failureCategory;

    /** 创建时间 */
    private Instant createdAt;

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public ToolCallStatusEnum getStatus() {
        return status;
    }

    public void setStatus(ToolCallStatusEnum status) {
        this.status = status;
    }

    public boolean isIdempotent() {
        return idempotent;
    }

    public void setIdempotent(boolean idempotent) {
        this.idempotent = idempotent;
    }

    public RiskLevelEnum getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevelEnum riskLevel) {
        this.riskLevel = riskLevel;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }

    public FailureCategoryEnum getFailureCategory() {
        return failureCategory;
    }

    public void setFailureCategory(FailureCategoryEnum failureCategory) {
        this.failureCategory = failureCategory;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
