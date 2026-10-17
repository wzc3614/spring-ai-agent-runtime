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
package com.agentruntime.observability;

import com.agentruntime.api.domain.EventRecord;
import com.agentruntime.api.domain.Span;
import com.agentruntime.api.observability.ObservabilitySink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 日志 Sink：永远在线，把 span/event 结构化写入 slf4j（英文）。
 * 零基建依赖，dev/调试与无 DB 场景的默认可观测通道。
 */
public class LoggingObservabilitySink implements ObservabilitySink {

    private static final Logger log = LoggerFactory.getLogger(LoggingObservabilitySink.class);

    @Override
    public void writeSpan(Span span) {
        log.info("span: id={}, type={}, session={}, parent={}, started={}, ended={}",
                span.getSpanId(),
                span.getSpanType().getCode(),
                span.getSessionId(),
                span.getParentSpanId(),
                span.getStartedAt(),
                span.getEndedAt());
    }

    @Override
    public void writeEvent(EventRecord event) {
        log.info("event: id={}, span={}, type={}, occurred={}",
                event.getEventId(),
                event.getSpanId(),
                event.getEventType().getCode(),
                event.getOccurredAt());
    }
}
