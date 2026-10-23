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
 *
 * <p>同步 {@link #call} 与流式 {@link #stream} 对称埋点。流式在订阅时开 span，
 * 随 chunk 累积 usage/finishReason（以后到的非空值为准），在 complete/error/cancel 时关 span。
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
        Span span = recorder.startSpan(SpanTypeEnum.MODEL_CALL, SpanContextHolder.parentSpanId(),
                SpanContextHolder.sessionId());
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
        // defer：订阅时才开 span，避免未订阅的 Flux 泄漏；reactor 线程上 ThreadLocal 已由 context-propagation 恢复
        return Flux.defer(() -> {
            Span span = recorder.startSpan(SpanTypeEnum.MODEL_CALL, SpanContextHolder.parentSpanId(),
                    SpanContextHolder.sessionId());
            StreamUsageAccumulator accumulator = new StreamUsageAccumulator();
            return delegate.stream(prompt)
                    .doOnNext(accumulator::accept)
                    .doFinally(signal -> {
                        applyAttributes(span, prompt, accumulator);
                        recorder.endSpan(span.getSpanId());
                    });
        });
    }

    private void applyAttributes(Span span, Prompt prompt, ChatResponse response) {
        StreamUsageAccumulator accumulator = new StreamUsageAccumulator();
        accumulator.accept(response);
        applyAttributes(span, prompt, accumulator);
    }

    private void applyAttributes(Span span, Prompt prompt, StreamUsageAccumulator accumulator) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (prompt.getOptions() != null && prompt.getOptions().getModel() != null) {
            attrs.put("modelName", prompt.getOptions().getModel());
        }
        if (accumulator.getModelName() != null) {
            attrs.put("modelName", accumulator.getModelName());
        }
        if (accumulator.getInputTokens() != null) {
            attrs.put("inputTokens", accumulator.getInputTokens());
        }
        if (accumulator.getOutputTokens() != null) {
            attrs.put("outputTokens", accumulator.getOutputTokens());
        }
        if (accumulator.getFinishReason() != null) {
            attrs.put("finishReason", accumulator.getFinishReason());
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

    /**
     * 流式 chunk 的 usage 累积器：以后到的非空 modelName / tokens / finishReason 覆盖。
     * 多数网关只在最后一个 chunk 带 usage，这样能拿到完整值。
     */
    private static class StreamUsageAccumulator {

        private String modelName;
        private Integer inputTokens;
        private Integer outputTokens;
        private String finishReason;

        void accept(ChatResponse response) {
            if (response == null || response.getMetadata() == null) {
                acceptFinishReason(response);
                return;
            }
            if (response.getMetadata().getModel() != null) {
                this.modelName = response.getMetadata().getModel();
            }
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                if (usage.getPromptTokens() != null) {
                    this.inputTokens = usage.getPromptTokens();
                }
                if (usage.getCompletionTokens() != null) {
                    this.outputTokens = usage.getCompletionTokens();
                }
            }
            acceptFinishReason(response);
        }

        private void acceptFinishReason(ChatResponse response) {
            if (response == null || response.getResult() == null
                    || response.getResult().getMetadata() == null) {
                return;
            }
            String reason = response.getResult().getMetadata().getFinishReason();
            if (reason != null) {
                this.finishReason = reason;
            }
        }

        String getModelName() {
            return modelName;
        }

        Integer getInputTokens() {
            return inputTokens;
        }

        Integer getOutputTokens() {
            return outputTokens;
        }

        String getFinishReason() {
            return finishReason;
        }
    }
}
