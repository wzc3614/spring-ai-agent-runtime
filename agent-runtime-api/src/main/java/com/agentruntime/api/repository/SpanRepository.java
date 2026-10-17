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

import com.agentruntime.api.domain.Span;

import java.util.List;
import java.util.Optional;

/**
 * Span 仓储，对应 agent_runtime_obs.span 表。
 */
public interface SpanRepository {

    /**
     * 保存 Span。
     *
     * @param span Span 实体
     * @return 保存后的实体
     */
    Span save(Span span);

    /**
     * 根据 Span ID 查询。
     *
     * @param spanId Span ID
     * @return Span 实体
     */
    Optional<Span> findById(String spanId);

    /**
     * 查询会话的执行树。
     *
     * @param sessionId 会话 ID
     * @return Span 列表
     */
    List<Span> findBySession(String sessionId);

    /**
     * 查询某父 Span 的直接子 Span。
     *
     * @param parentSpanId 父 Span ID
     * @return 子 Span 列表
     */
    List<Span> findByParent(String parentSpanId);
}
