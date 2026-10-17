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
import com.agentruntime.api.enums.SpanTypeEnum;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.core.springai.SpanContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 观测式 ChatModel 装饰器：每次 LLM 调用建一个 MODEL_CALL（LlmCallSpan），
 * 从响应元数据采集 modelName/finishReason/inputTokens/outputTokens 写入 span 属性。
 * 父 span 与 sessionId 来自 {@link SpanContextHolder}（由编排器在调用前设置）。
 * 流式路径暂不埋点（后续接）。
 */
public class ObservingChatModel implements ChatModel {

    private final ChatModel delegate;

    private final ObservabilityRecorder recorder;

    private final ObjectMapper objectMapper;

    public ObservingChatModel(ChatModel delegate, ObservabilityRecorder recorder, ObjectMapper objectMapper) {
        this.delegate = delegate;
        this.recorder = recorder;
        this.objectMapper = objectMapper;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Span span = recorder.startSpan(SpanTypeEnum.MODEL_CALL, SpanContextHolder.parentSpanId(), SpanContextHolder.sessionId());
        try {
            ChatResponse response = delegate.call(prompt);
            applyAttributes(span, prompt, response);
            return response;
        } finally {
            recorder.endSpan(span.getSpanId());
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // 流式埋点暂缓，直接委托
        return delegate.stream(prompt);
    }

    private void applyAttributes(Span span, Prompt prompt, ChatResponse response) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (prompt.getOptions() != null && prompt.getOptions().getModel() != null) {
            attrs.put("modelName", prompt.getOptions().getModel());
        }
        if (response != null && response.getMetadata() != null) {
            if (response.getMetadata().getModel() != null) {
                attrs.put("modelName", response.getMetadata().getModel());
            }
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                if (usage.getPromptTokens() != null) {
                    attrs.put("inputTokens", usage.getPromptTokens());
                }
                if (usage.getCompletionTokens() != null) {
                    attrs.put("outputTokens", usage.getCompletionTokens());
                }
            }
            if (response.getResult() != null
                    && response.getResult().getMetadata() != null
                    && response.getResult().getMetadata().getFinishReason() != null) {
                attrs.put("finishReason", response.getResult().getMetadata().getFinishReason());
            }
        }
        span.setAttributes(toJson(attrs));
    }

    private String toJson(Map<String, Object> attrs) {
        if (attrs.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(attrs);
        } catch (Exception e) {
            return null;
        }
    }
}
