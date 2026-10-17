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

import com.agentruntime.api.enums.MessageRoleEnum;

import java.time.Instant;

/**
 * 消息实体，对应 agent_message 表。只追加、不删除；compacted=true 表示已被微压缩替换为占位符。
 */
public class AgentMessage {

    /** 消息唯一 ID */
    private String messageId;

    /** 所属会话 ID */
    private String sessionId;

    /** 会话内单调递增序号，保证顺序 */
    private long seq;

    /** 消息角色 */
    private MessageRoleEnum role;

    /** 消息内容（原始文本或 JSON 字符串） */
    private String content;

    /** 是否已被微压缩 */
    private boolean compacted;

    /** 微压缩后的证据指针 */
    private String evidenceId;

    /** 创建时间 */
    private Instant createdAt;

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public MessageRoleEnum getRole() {
        return role;
    }

    public void setRole(MessageRoleEnum role) {
        this.role = role;
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

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
