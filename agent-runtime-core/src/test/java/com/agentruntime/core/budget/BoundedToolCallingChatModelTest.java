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
package com.agentruntime.core.budget;

import com.agentruntime.api.budget.TurnBudget;
import com.agentruntime.api.budget.TurnBudgetConstants;
import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.domain.Span;
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.SpanTypeEnum;
import com.agentruntime.api.enums.TurnBudgetReasonEnum;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.core.springai.SpanContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedToolCallingChatModelTest {

    @AfterEach
    void clearSpan() {
        SpanContextHolder.clear();
    }

    @Test
    void stopsAtSixthModelCallWhenToolsContinue() {
        ScriptedChatModel delegate = new ScriptedChatModel(repeat(toolCall(1, 1), TurnBudgetConstants.DEFAULT_MAX_MODEL_CALLS));
        CountingToolManager tools = new CountingToolManager();
        RecordingRecorder recorder = new RecordingRecorder();
        BoundedToolCallingChatModel model = model(delegate, tools, recorder, TurnBudget.defaults(), new FixedEstimator(0));

        SpanContextHolder.set("turn-1", "s1");
        ChatResponse response = model.call(prompt());

        assertEquals(TurnBudgetConstants.DEFAULT_MAX_MODEL_CALLS, delegate.calls);
        assertEquals(TurnBudgetConstants.DEFAULT_MAX_MODEL_CALLS - 1, tools.executions);
        assertEquals(TurnBudgetConstants.STOP_TEXT, response.getResult().getOutput().getText());
        assertEquals(Boolean.FALSE, ((ToolCallingChatOptions) delegate.lastOptions).getInternalToolExecutionEnabled());
        assertEquals(EventTypeEnum.TURN_BUDGET_EXCEEDED, recorder.type);
        assertTrue(recorder.payload.contains(TurnBudgetReasonEnum.STEP_LIMIT.getCode()));
        assertTrue(recorder.payload.contains("\"modelCalls\":6"));
    }

    @Test
    void stopsWhenTurnTokensExceeded() {
        ScriptedChatModel delegate = new ScriptedChatModel(repeat(toolCall(40, 20), 4));
        CountingToolManager tools = new CountingToolManager();
        RecordingRecorder recorder = new RecordingRecorder();
        TurnBudget budget = TurnBudget.defaults();
        budget.setMaxTurnTokens(100);
        BoundedToolCallingChatModel model = model(delegate, tools, recorder, budget, new FixedEstimator(0));

        SpanContextHolder.set("turn-1", "s1");
        ChatResponse response = model.call(prompt());

        assertEquals(2, delegate.calls);
        assertEquals(1, tools.executions);
        assertEquals(TurnBudgetConstants.STOP_TEXT, response.getResult().getOutput().getText());
        assertTrue(recorder.payload.contains(TurnBudgetReasonEnum.TOKEN_LIMIT.getCode()));
        assertTrue(recorder.payload.contains("\"totalTokens\":120"));
    }

    @Test
    void returnsFinalAnswerAfterTokensAreAlreadySpent() {
        ScriptedChatModel delegate = new ScriptedChatModel(List.of(answer("done", 80, 40)));
        CountingToolManager tools = new CountingToolManager();
        RecordingRecorder recorder = new RecordingRecorder();
        TurnBudget budget = TurnBudget.defaults();
        budget.setMaxTurnTokens(10);
        BoundedToolCallingChatModel model = model(delegate, tools, recorder, budget, new FixedEstimator(0));

        ChatResponse response = model.call(prompt());

        assertEquals("done", response.getResult().getOutput().getText());
        assertEquals(1, delegate.calls);
        assertEquals(0, tools.executions);
        assertEquals(0, recorder.events);
    }

    @Test
    void finishesToolRoundThenReturnsAnswer() {
        ScriptedChatModel delegate = new ScriptedChatModel(List.of(toolCall(10, 5), answer("done", 10, 5)));
        CountingToolManager tools = new CountingToolManager();
        RecordingRecorder recorder = new RecordingRecorder();
        BoundedToolCallingChatModel model = model(delegate, tools, recorder, TurnBudget.defaults(), new FixedEstimator(0));

        ChatResponse response = model.call(prompt());

        assertEquals("done", response.getResult().getOutput().getText());
        assertEquals(2, delegate.calls);
        assertEquals(1, tools.executions);
        assertEquals(0, recorder.events);
    }

    @Test
    void estimatesTokensWhenUsageMissing() {
        ScriptedChatModel delegate = new ScriptedChatModel(repeat(toolCall(-1, -1), 4));
        CountingToolManager tools = new CountingToolManager();
        RecordingRecorder recorder = new RecordingRecorder();
        TurnBudget budget = TurnBudget.defaults();
        budget.setMaxTurnTokens(50);
        BoundedToolCallingChatModel model = model(delegate, tools, recorder, budget, new FixedEstimator(30));

        SpanContextHolder.set("turn-1", "s1");
        ChatResponse response = model.call(prompt());

        assertEquals(2, delegate.calls);
        assertEquals(1, tools.executions);
        assertEquals(TurnBudgetConstants.STOP_TEXT, response.getResult().getOutput().getText());
        assertTrue(recorder.payload.contains(TurnBudgetReasonEnum.TOKEN_LIMIT.getCode()));
    }

    @Test
    void streamStopsWithoutEmittingToolChunks() {
        TurnBudget budget = TurnBudget.defaults();
        budget.setMaxModelCalls(2);
        ScriptedChatModel delegate = new ScriptedChatModel(repeat(toolCall(1, 1), 4));
        CountingToolManager tools = new CountingToolManager();
        RecordingRecorder recorder = new RecordingRecorder();
        BoundedToolCallingChatModel model = model(delegate, tools, recorder, budget, new FixedEstimator(0));

        SpanContextHolder.set("turn-1", "s1");
        List<ChatResponse> responses = model.stream(prompt()).collectList().block();

        assertEquals(2, delegate.calls);
        assertEquals(1, tools.executions);
        assertEquals(1, responses.size());
        assertEquals(TurnBudgetConstants.STOP_TEXT, responses.get(0).getResult().getOutput().getText());
        assertTrue(recorder.payload.contains(TurnBudgetReasonEnum.STEP_LIMIT.getCode()));
    }

    @Test
    void streamEmitsOnlyTheFinalAnswer() {
        ScriptedChatModel delegate = new ScriptedChatModel(List.of(toolCall(1, 1), answer("done", 1, 1)));
        CountingToolManager tools = new CountingToolManager();
        BoundedToolCallingChatModel model = model(delegate, tools, new RecordingRecorder(),
                TurnBudget.defaults(), new FixedEstimator(0));

        List<ChatResponse> responses = model.stream(prompt()).collectList().block();

        assertEquals(1, responses.size());
        assertEquals("done", responses.get(0).getResult().getOutput().getText());
        assertEquals(2, delegate.calls);
        assertEquals(1, tools.executions);
    }

    @Test
    void returnDirectSkipsAnotherModelCall() {
        ScriptedChatModel delegate = new ScriptedChatModel(List.of(toolCall(1, 1), answer("should-not-run", 1, 1)));
        CountingToolManager tools = new CountingToolManager();
        tools.returnDirect = true;
        BoundedToolCallingChatModel model = model(delegate, tools, new RecordingRecorder(),
                TurnBudget.defaults(), new FixedEstimator(0));

        ChatResponse response = model.call(prompt());

        assertEquals(1, delegate.calls);
        assertEquals(1, tools.executions);
        assertEquals("observed", response.getResult().getOutput().getText());
    }

    private static BoundedToolCallingChatModel model(ScriptedChatModel delegate, CountingToolManager tools,
                                                     RecordingRecorder recorder, TurnBudget budget,
                                                     TokenEstimator estimator) {
        return new BoundedToolCallingChatModel(delegate, tools, budget, recorder, estimator);
    }

    private static Prompt prompt() {
        DefaultToolCallingChatOptions options = new DefaultToolCallingChatOptions();
        options.setInternalToolExecutionEnabled(Boolean.TRUE);
        return new Prompt(new UserMessage("hi"), options);
    }

    private static List<ChatResponse> repeat(ChatResponse response, int times) {
        List<ChatResponse> responses = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            responses.add(response);
        }
        return responses;
    }

    private static ChatResponse toolCall(int promptTokens, int completionTokens) {
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall("call-1", "function", "lookup", "{}");
        AssistantMessage assistant = new AssistantMessage("", Map.of(), List.of(toolCall));
        return withUsage(new Generation(assistant), promptTokens, completionTokens);
    }

    private static ChatResponse answer(String text, int promptTokens, int completionTokens) {
        return withUsage(new Generation(new AssistantMessage(text)), promptTokens, completionTokens);
    }

    private static ChatResponse withUsage(Generation generation, int promptTokens, int completionTokens) {
        if (promptTokens < 0) {
            return new ChatResponse(List.of(generation));
        }
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new DefaultUsage(promptTokens, completionTokens))
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    private static final class ScriptedChatModel implements ChatModel {

        private final List<ChatResponse> script;

        private int cursor;

        private int calls;

        private ChatOptions lastOptions;

        private ScriptedChatModel(List<ChatResponse> script) {
            this.script = script;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            return next(prompt);
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(next(prompt));
        }

        private ChatResponse next(Prompt prompt) {
            calls++;
            lastOptions = prompt.getOptions();
            if (cursor >= script.size()) {
                throw new IllegalStateException("model called more times than scripted: " + calls);
            }
            return script.get(cursor++);
        }
    }

    private static final class CountingToolManager implements ToolCallingManager {

        private int executions;

        private boolean returnDirect;

        @Override
        public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
            return List.of();
        }

        @Override
        public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
            executions++;
            List<Message> history = new ArrayList<>(prompt.getInstructions());
            AssistantMessage assistant = chatResponse.getResult().getOutput();
            history.add(assistant);
            AssistantMessage.ToolCall toolCall = assistant.getToolCalls().get(0);
            history.add(new ToolResponseMessage(List.of(
                    new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), "observed"))));
            return ToolExecutionResult.builder()
                    .conversationHistory(history)
                    .returnDirect(returnDirect)
                    .build();
        }
    }

    private static final class RecordingRecorder implements ObservabilityRecorder {

        private EventTypeEnum type;

        private String payload;

        private int events;

        @Override
        public Span startSpan(SpanTypeEnum spanType, String parentSpanId, String sessionId) {
            return new Span();
        }

        @Override
        public void endSpan(String spanId) {
        }

        @Override
        public void recordEvent(String spanId, EventTypeEnum eventType, String eventPayload) {
            this.events++;
            this.type = eventType;
            this.payload = eventPayload;
        }
    }

    private static final class FixedEstimator implements TokenEstimator {

        private final int perText;

        private FixedEstimator(int perText) {
            this.perText = perText;
        }

        @Override
        public int estimate(String text) {
            if (text == null || text.isEmpty()) {
                return 0;
            }
            return perText;
        }

        @Override
        public int estimateJson(String json) {
            return estimate(json);
        }

        @Override
        public int estimateMessages(List<AgentMessage> messages) {
            return 0;
        }
    }
}
