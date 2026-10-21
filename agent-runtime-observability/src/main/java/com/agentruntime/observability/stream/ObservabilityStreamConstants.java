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
 * Observability Redis Stream 异步写入相关常量。集中管理 stream key、消费组、字段名、
 * 消息类型等，避免裸字面量散落代码。
 */
public final class ObservabilityStreamConstants {

    /** span/event 共用 Stream key */
    public static final String STREAM_KEY = "agentruntime:obs:log:stream";

    /** 消费组名 */
    public static final String CONSUMER_GROUP = "obs-consumer-group";

    /** 死信 Stream key */
    public static final String DLQ_STREAM_KEY = "agentruntime:obs:log:dlq";

    /** Stream 字段：消息类型（SPAN / EVENT） */
    public static final String FIELD_TYPE = "type";

    /** Stream 字段：载荷 JSON（Span 或 EventRecord 序列化） */
    public static final String FIELD_PAYLOAD = "payload";

    /** Stream 字段：所属 span id */
    public static final String FIELD_SPAN_ID = "spanId";

    /** Stream 字段：所属 session id */
    public static final String FIELD_SESSION_ID = "sessionId";

    /** Stream 字段：创建时间（epoch millis） */
    public static final String FIELD_CREATED_AT = "createdAt";

    /** Stream 字段：重试次数（DLQ 用） */
    public static final String FIELD_RETRY = "retryCount";

    /** Stream 字段：失败原因（DLQ 用） */
    public static final String FIELD_REASON = "failReason";

    /** 消息类型：span */
    public static final String TYPE_SPAN = "SPAN";

    /** 消息类型：event */
    public static final String TYPE_EVENT = "EVENT";

    private ObservabilityStreamConstants() {
    }
}
