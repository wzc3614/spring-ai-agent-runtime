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
 * Spill 元数据实体，对应 agent_spill_record 表。完整原文存于 MinIO/S3 对象存储。
 */
public class SpillRecord {

    /** Spill 唯一 ID */
    private String spillId;

    /** 关联的工具调用 ID */
    private String toolCallId;

    /** MinIO/S3 对象 key */
    private String minioObjectKey;

    /** 原始内容大小（字符数） */
    private int originalSize;

    /** 注入上下文的预览文本 */
    private String preview;

    /** 过期时间（会话结束后批量清理） */
    private Instant expiresAt;

    /** 创建时间 */
    private Instant createdAt;

    public String getSpillId() {
        return spillId;
    }

    public void setSpillId(String spillId) {
        this.spillId = spillId;
    }

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getMinioObjectKey() {
        return minioObjectKey;
    }

    public void setMinioObjectKey(String minioObjectKey) {
        this.minioObjectKey = minioObjectKey;
    }

    public int getOriginalSize() {
        return originalSize;
    }

    public void setOriginalSize(int originalSize) {
        this.originalSize = originalSize;
    }

    public String getPreview() {
        return preview;
    }

    public void setPreview(String preview) {
        this.preview = preview;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
