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
package com.agentruntime.memory.write;

/**
 * 写入任务，序列化后存入 Redis FIFO 队列。携带 message 级原子字段，避免 Instant 序列化依赖。
 * taskId 为幂等键；retryCount 由 worker 失败重投时递增。
 */
public class WriteTask {

    /** 幂等任务 ID（sha256） */
    private String taskId;

    /** 所属会话 ID（生命周期 + 记忆作用域，队列键 + 消息 session_id） */
    private String sessionId;

    /** 本轮 ID */
    private String turnId;

    /** 消息 ID */
    private String messageId;

    /** 预分配 seq */
    private long seq;

    /** 角色存储 code */
    private String roleCode;

    /** 消息内容 */
    private String content;

    /** 是否已微压缩 */
    private boolean compacted;

    /** 证据指针 */
    private String evidenceId;

    /** 工具原文 JSON，key 为工具调用 id */
    private String payload;

    /** 整条消息的压缩分类 */
    private String compactionClass;

    /** 创建时间（epoch millis） */
    private long createdAtEpochMillis;

    /** 重试次数 */
    private int retryCount;

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getRoleCode() {
        return roleCode;
    }

    public void setRoleCode(String roleCode) {
        this.roleCode = roleCode;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public boolean isCompacted() {
        return compacted;
    }

    public void setCompacted(boolean compacted) {
        this.compacted = compacted;
    }

    public String getEvidenceId() {
        return evidenceId;
    }

    public void setEvidenceId(String evidenceId) {
        this.evidenceId = evidenceId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getCompactionClass() {
        return compactionClass;
    }

    public void setCompactionClass(String compactionClass) {
        this.compactionClass = compactionClass;
    }

    public long getCreatedAtEpochMillis() {
        return createdAtEpochMillis;
    }

    public void setCreatedAtEpochMillis(long createdAtEpochMillis) {
        this.createdAtEpochMillis = createdAtEpochMillis;
    }

    public int getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(int retryCount) {
        this.retryCount = retryCount;
    }
}
