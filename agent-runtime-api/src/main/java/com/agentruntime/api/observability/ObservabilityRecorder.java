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

import com.agentruntime.api.domain.Span;
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.SpanTypeEnum;

/**
 * 可观测记录器端口。编排器用它构建 Span/Event 树；实现负责分发到各 Sink 持久化。
 */
public interface ObservabilityRecorder {

    /**
     * 开启一个 span。
     *
     * @param type          span 类型
     * @param parentSpanId  父 span ID（顶层传 null）
     * @param sessionId     会话 ID
     * @return 带新生成 id 的 Span
     */
    Span startSpan(SpanTypeEnum type, String parentSpanId, String sessionId);

    /**
     * 关闭 span（设 endedAt 并下发 Sink）。
     *
     * @param spanId span ID
     */
    void endSpan(String spanId);

    /**
     * 在某 span 下记录事件并立即下发 Sink。
     *
     * @param spanId  所属 span ID
     * @param type    事件类型
     * @param payload 事件载荷（JSON 字符串）
     */
    void recordEvent(String spanId, EventTypeEnum type, String payload);
}
