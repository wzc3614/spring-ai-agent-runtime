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
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.SpanTypeEnum;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.observability.reasoning.ReasoningCapture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型调用上的思考过程：网关字段优先，工具旁边的正文其次，纯回答不记。
 */
class ObservingChatModelThinkingTest {

    @AfterEach
    void clearCapture() {
        ReasoningCapture.clear();
    }

    @Test
    void recordsReasoningContentOnTheModelCall() {
        MemoryRecorder recorder = new MemoryRecorder();
        ChatModel delegate = new ScriptedChatModel(prompt -> {
            ReasoningCapture.append("网关思考");
            return answer("最终回答", false);
        }, prompt -> Flux.just(answer("最终回答", false)));
        ObservingChatModel model = capturing(delegate, recorder);

        model.call(new Prompt("hi"));

        assertThat(recorder.events).extracting(EventRecord::getEventType).containsExactly(EventTypeEnum.THINKING);
        assertThat(recorder.events.get(0).getPayload()).isEqualTo("网关思考");
        assertThat(ReasoningCapture.current()).isNull();
    }

    @Test
    void recordsToolPreambleWhenReasoningIsAbsent() {
        MemoryRecorder recorder = new MemoryRecorder();
        ChatModel delegate = new ScriptedChatModel(prompt -> answer("先查用户", true), prompt -> Flux.empty());
        ObservingChatModel model = capturing(delegate, recorder);

        model.call(new Prompt("hi"));

        assertThat(recorder.events).singleElement().extracting(EventRecord::getPayload).isEqualTo("先查用户");
    }

    @Test
    void skipsPlainAnswer() {
        MemoryRecorder recorder = new MemoryRecorder();
        ChatModel delegate = new ScriptedChatModel(prompt -> answer("最终回答", false), prompt -> Flux.empty());
        ObservingChatModel model = capturing(delegate, recorder);

        model.call(new Prompt("hi"));

        assertThat(recorder.events).isEmpty();
    }

    @Test
    void stitchesStreamReasoningBeforeTheToolPreamble() {
        MemoryRecorder recorder = new MemoryRecorder();
        ChatModel delegate = new ScriptedChatModel(prompt -> answer("不会走到", false), prompt -> Flux.defer(() -> {
            ReasoningCapture.append("甲");
            ReasoningCapture.append("乙");
            return Flux.just(answer("旁白", true));
        }));
        ObservingChatModel model = capturing(delegate, recorder);

        model.stream(new Prompt("hi")).blockLast();

        assertThat(recorder.events).singleElement().extracting(EventRecord::getPayload).isEqualTo("甲乙");
        assertThat(ReasoningCapture.current()).isNull();
    }

    @Test
    void leavesOtherModelsUnrecorded() {
        MemoryRecorder recorder = new MemoryRecorder();
        ChatModel delegate = new ScriptedChatModel(prompt -> {
            ReasoningCapture.append("路由思考");
            return answer("order-query", false);
        }, prompt -> Flux.empty());
        ObservingChatModel model = new ObservingChatModel(delegate, recorder, new ObjectMapper());

        model.call(new Prompt("hi"));

        assertThat(recorder.events).isEmpty();
    }

    private ObservingChatModel capturing(ChatModel delegate, MemoryRecorder recorder) {
        return new ObservingChatModel(delegate, recorder, new ObjectMapper(), true);
    }

    private ChatResponse answer(String text, boolean toolCall) {
        AssistantMessage message = toolCall
                ? new AssistantMessage(text, Map.of(), List.of(new AssistantMessage.ToolCall(
                        "1", "function", "query_user_info", "{}")))
                : new AssistantMessage(text);
        return new ChatResponse(List.of(new Generation(message)));
    }

    @FunctionalInterface
    private interface Call {
        ChatResponse reply(Prompt prompt);
    }

    @FunctionalInterface
    private interface StreamCall {
        Flux<ChatResponse> reply(Prompt prompt);
    }

    private static final class ScriptedChatModel implements ChatModel {

        private final Call call;

        private final StreamCall stream;

        private ScriptedChatModel(Call call, StreamCall stream) {
            this.call = call;
            this.stream = stream;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return call.reply(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return stream.reply(prompt);
        }
    }

    private static final class MemoryRecorder implements ObservabilityRecorder {

        private final List<EventRecord> events = new ArrayList<>();

        @Override
        public Span startSpan(SpanTypeEnum type, String parentSpanId, String sessionId) {
            Span span = new Span();
            span.setSpanId("m1");
            span.setSpanType(type);
            span.setParentSpanId(parentSpanId);
            span.setSessionId(sessionId);
            return span;
        }

        @Override
        public void endSpan(String spanId) {
        }

        @Override
        public void recordEvent(String spanId, EventTypeEnum type, String payload) {
            EventRecord event = new EventRecord();
            event.setSpanId(spanId);
            event.setEventType(type);
            event.setPayload(payload);
            events.add(event);
        }
    }
}
