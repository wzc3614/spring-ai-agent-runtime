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
import com.agentruntime.api.repository.EventRepository;
import com.agentruntime.api.repository.SpanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC Sink：把 span/event 落库到 agent_runtime_obs。条件装配（DataSource 存在时启用）。
 */
public class JdbcObservabilitySink implements ObservabilitySink {

    private static final Logger log = LoggerFactory.getLogger(JdbcObservabilitySink.class);

    private final SpanRepository spanRepository;

    private final EventRepository eventRepository;

    public JdbcObservabilitySink(SpanRepository spanRepository, EventRepository eventRepository) {
        this.spanRepository = spanRepository;
        this.eventRepository = eventRepository;
    }

    @Override
    public void writeSpan(Span span) {
        try {
            spanRepository.save(span);
        } catch (Exception e) {
            log.warn("persist span failed, spanId={}", span.getSpanId(), e);
        }
    }

    @Override
    public void writeEvent(EventRecord event) {
        try {
            eventRepository.save(event);
        } catch (Exception e) {
            log.warn("persist event failed, eventId={}", event.getEventId(), e);
        }
    }
}
