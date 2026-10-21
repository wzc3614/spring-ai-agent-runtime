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

import com.agentruntime.api.domain.EventRecord;
import com.agentruntime.api.domain.Span;
import com.agentruntime.api.observability.ObservabilitySink;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Redis Stream 异步 Sink：把 span/event 序列化为 Stream 消息 XADD，落库由独立 consumer 异步完成。
 * 请求线程只承担一次 XADD，DB 写入延迟与失败不影响主流程。异常仅 warn，不抛出（与
 * {@code JdbcObservabilitySink} 行为一致）。
 */
public class RedisStreamObservabilitySink implements ObservabilitySink {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamObservabilitySink.class);

    private final ObservabilityStreamQueue queue;

    private final ObjectMapper objectMapper;

    public RedisStreamObservabilitySink(ObservabilityStreamQueue queue, ObjectMapper objectMapper) {
        this.queue = queue;
        this.objectMapper = objectMapper;
    }

    @Override
    public void writeSpan(Span span) {
        try {
            queue.enqueue(toTask(ObservabilityStreamConstants.TYPE_SPAN, span.getSpanId(),
                    span.getSessionId(), span));
        } catch (Exception e) {
            log.warn("enqueue span failed, spanId={}", span.getSpanId(), e);
        }
    }

    @Override
    public void writeEvent(EventRecord event) {
        try {
            queue.enqueue(toTask(ObservabilityStreamConstants.TYPE_EVENT, event.getSpanId(),
                    null, event));
        } catch (Exception e) {
            log.warn("enqueue event failed, eventId={}", event.getEventId(), e);
        }
    }

    private ObservabilityWriteTask toTask(String type, String spanId, String sessionId, Object entity) {
        ObservabilityWriteTask task = new ObservabilityWriteTask();
        task.setType(type);
        task.setSpanId(spanId);
        task.setSessionId(sessionId);
        task.setPayload(writeJson(entity));
        task.setCreatedAtEpochMillis(System.currentTimeMillis());
        return task;
    }

    private String writeJson(Object entity) {
        try {
            return objectMapper.writeValueAsString(entity);
        } catch (Exception e) {
            throw new IllegalStateException("serialize observability entity failed", e);
        }
    }
}
