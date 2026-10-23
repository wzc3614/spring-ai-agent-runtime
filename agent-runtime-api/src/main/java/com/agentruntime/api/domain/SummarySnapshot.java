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

import java.time.Instant;

/**
 * 累积摘要快照，对应 agent_summary_snapshot 表。
 * version 支持增量合并更新（"摘要之上再摘要"），sections 为通用内容 JSON（当前存一段式摘要，
 * 后续可扩展 coreGoal/taskProgress 等字段），summarizedUpToSeq 标识已摘要覆盖的 message seq 边界。
 */
public class SummarySnapshot {

    /** 摘要唯一 ID */
    private String summaryId;

    /** 所属会话 ID */
    private String sessionId;

    /** 版本号 */
    private int version;

    /** 通用内容 JSON 字符串（由 memory 层负责与 SummaryContent 互转，当前存一段式摘要，可演进） */
    private String sections;

    /** 该摘要已覆盖到的最大 message seq（不含），seq > 此值的历史仍以原文注入 */
    private long summarizedUpToSeq;

    /** 压缩状态：SUCCESS / PARTIAL / FAILED（见 SummaryStatusEnum），默认 SUCCESS 兼容历史行 */
    private String status;

    /** 压缩失败原因（status=FAILED 时填充） */
    private String errorMessage;

    /** 更新时间 */
    private Instant updatedAt;

    public String getSummaryId() {
        return summaryId;
    }

    public void setSummaryId(String summaryId) {
        this.summaryId = summaryId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getSections() {
        return sections;
    }

    public void setSections(String sections) {
        this.sections = sections;
    }

    public long getSummarizedUpToSeq() {
        return summarizedUpToSeq;
    }

    public void setSummarizedUpToSeq(long summarizedUpToSeq) {
        this.summarizedUpToSeq = summarizedUpToSeq;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
