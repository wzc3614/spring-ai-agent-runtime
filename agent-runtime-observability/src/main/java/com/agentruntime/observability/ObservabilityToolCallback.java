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

import com.agentruntime.api.domain.Span;
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.SpanTypeEnum;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.core.springai.BaseToolCallbackDecorator;
import com.agentruntime.core.springai.SpanContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.Collections;

/**
 * 观测式 ToolCallback 装饰器：每次工具调用建一个 TOOL_CALL（ToolCallSpan），
 * 记录 TOOL_INPUT/TOOL_OUTPUT 事件（失败记 ERROR），span 属性带 toolName。
 * 父 span 与 sessionId 来自 {@link SpanContextHolder}。
 */
public class ObservabilityToolCallback extends BaseToolCallbackDecorator {

    private final ObservabilityRecorder recorder;

    private final ObjectMapper objectMapper;

    public ObservabilityToolCallback(ToolCallback delegate, ObservabilityRecorder recorder, ObjectMapper objectMapper) {
        super(delegate);
        this.recorder = recorder;
        this.objectMapper = objectMapper;
    }

    @Override
    public String call(String toolInput) {
        return observe(toolInput, () -> delegate().call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext context) {
        return observe(toolInput, () -> delegate().call(toolInput, context));
    }

    private String observe(String toolInput, java.util.function.Supplier<String> action) {
        Span span = recorder.startSpan(SpanTypeEnum.TOOL_CALL, SpanContextHolder.parentSpanId(),
                SpanContextHolder.sessionId());
        applyToolName(span);
        recorder.recordEvent(span.getSpanId(), EventTypeEnum.TOOL_INPUT, toolInput);
        try {
            String output = action.get();
            recorder.recordEvent(span.getSpanId(), EventTypeEnum.TOOL_OUTPUT, output);
            return output;
        } catch (RuntimeException e) {
            recorder.recordEvent(span.getSpanId(), EventTypeEnum.ERROR, e.getMessage());
            throw e;
        } finally {
            recorder.endSpan(span.getSpanId());
        }
    }

    private void applyToolName(Span span) {
        // GA: 工具名经 ToolDefinition.name() 获取
        String toolName = delegate().getToolDefinition().name();
        if (toolName == null) {
            return;
        }
        try {
            span.setAttributes(objectMapper.writeValueAsString(Collections.singletonMap("toolName", toolName)));
        } catch (Exception ignored) {
            // 序列化失败忽略，不影响工具调用
        }
    }
}
