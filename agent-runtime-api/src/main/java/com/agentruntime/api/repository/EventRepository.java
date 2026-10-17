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
package com.agentruntime.api.repository;

import com.agentruntime.api.domain.EventRecord;

import java.util.List;

/**
 * Event 仓储，对应 agent_runtime_obs.event_record 表。
 */
public interface EventRepository {

    /**
     * 保存 Event。
     *
     * @param event Event 实体
     * @return 保存后的实体
     */
    EventRecord save(EventRecord event);

    /**
     * 查询某 Span 下的全部 Event。
     *
     * @param spanId Span ID
     * @return Event 列表
     */
    List<EventRecord> findBySpan(String spanId);
}
