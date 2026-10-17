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
package com.agentruntime.api.observability;

import com.agentruntime.api.domain.EventRecord;
import com.agentruntime.api.domain.Span;

/**
 * 可观测持久化 Sink 端口。默认实现：{@code LoggingObservabilitySink}（永远在线）、
 * {@code JdbcObservabilitySink}（条件装配）。使用方可实现此接口接入公司内部日志系统 / ES / ClickHouse / OTLP。
 */
public interface ObservabilitySink {

    /**
     * 持久化一个 span（span 关闭时调用）。
     *
     * @param span span 实体
     */
    void writeSpan(Span span);

    /**
     * 持久化一个事件。
     *
     * @param event 事件实体
     */
    void writeEvent(EventRecord event);
}
