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

import com.agentruntime.api.enums.SpanTypeEnum;

import java.time.Instant;

/**
 * Span 实体，对应 agent_runtime_obs.span 表。可嵌套，还原"思考-行动-再思考"递归结构。
 */
public class Span {

    /** Span 唯一 ID */
    private String spanId;

    /** 父 Span ID */
    private String parentSpanId;

    /** 所属会话 ID */
    private String sessionId;

    /** Span 类型 */
    private SpanTypeEnum spanType;

    /** span 属性（JSON 字符串）：modelName/finishReason/inputTokens/outputTokens/toolName 等 */
    private String attributes;

    /** 开始时间 */
    private Instant startedAt;

    /** 结束时间 */
    private Instant endedAt;

    public String getSpanId() {
        return spanId;
    }

    public void setSpanId(String spanId) {
        this.spanId = spanId;
    }

    public String getParentSpanId() {
        return parentSpanId;
    }

    public void setParentSpanId(String parentSpanId) {
        this.parentSpanId = parentSpanId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public SpanTypeEnum getSpanType() {
        return spanType;
    }

    public void setSpanType(SpanTypeEnum spanType) {
        this.spanType = spanType;
    }

    public String getAttributes() {
        return attributes;
    }

    public void setAttributes(String attributes) {
        this.attributes = attributes;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(Instant endedAt) {
        this.endedAt = endedAt;
    }
}
