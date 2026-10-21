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
package com.agentruntime.observability.stream;

/**
 * Observability 异步写入任务。span / event 统一承载：type 区分类型，payload 为实体 JSON。
 * Instant 序列化为 epoch millis，避免 JSR310 依赖（对齐 memory 模块 WriteTask）。
 * entryId 为 Stream 分配的消息 id，仅在消费侧读取后回填，不写入 Stream 字段。
 */
public class ObservabilityWriteTask {

    /** 消息类型：SPAN / EVENT */
    private String type;

    /** 所属 span id（span 消息即自身，event 消息为外键） */
    private String spanId;

    /** 所属 session id */
    private String sessionId;

    /** 载荷 JSON（Span 或 EventRecord 序列化） */
    private String payload;

    /** 创建时间（epoch millis） */
    private long createdAtEpochMillis;

    /** 重试次数（消费失败递增） */
    private int retryCount;

    /** Stream entry id，消费侧回填，不持久化到 Stream 字段 */
    private String entryId;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getSpanId() {
        return spanId;
    }

    public void setSpanId(String spanId) {
        this.spanId = spanId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
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

    public String getEntryId() {
        return entryId;
    }

    public void setEntryId(String entryId) {
        this.entryId = entryId;
    }
}
