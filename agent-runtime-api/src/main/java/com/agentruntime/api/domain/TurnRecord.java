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

import com.agentruntime.api.enums.TurnStatusEnum;

import java.time.Instant;

/**
 * 一轮执行记录。租约字段只在运行中有意义，终态后清空。
 */
public class TurnRecord {

    /** 轮次 ID */
    private String turnId;

    /** 所属会话 */
    private String sessionId;

    /** 用户 ID */
    private String userId;

    /** 认领恢复时按它过滤，避免一个进程重跑另一个 agent */
    private String agentName;

    /** 本轮用户原文，崩溃后续跑用它，不依赖记忆里那一条 */
    private String query;

    /** 执行状态 */
    private TurnStatusEnum status;

    /** 当前租约持有者 */
    private String leaseOwner;

    /** 租约截止时间 */
    private Instant leaseDeadline;

    /** 失败或取消时的错误码 */
    private String errorCode;

    /** 完成后的回答全文，事件过期后回放用它补一帧 */
    private String finalAnswer;

    /** 创建时间 */
    private Instant createdAt;

    /** 最近一次状态变化 */
    private Instant updatedAt;

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getAgentName() {
        return agentName;
    }

    public void setAgentName(String agentName) {
        this.agentName = agentName;
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public TurnStatusEnum getStatus() {
        return status;
    }

    public void setStatus(TurnStatusEnum status) {
        this.status = status;
    }

    public String getLeaseOwner() {
        return leaseOwner;
    }

    public void setLeaseOwner(String leaseOwner) {
        this.leaseOwner = leaseOwner;
    }

    public Instant getLeaseDeadline() {
        return leaseDeadline;
    }

    public void setLeaseDeadline(Instant leaseDeadline) {
        this.leaseDeadline = leaseDeadline;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public void setErrorCode(String errorCode) {
        this.errorCode = errorCode;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
