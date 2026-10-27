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
package com.agentruntime.runtime;

import com.agentruntime.api.budget.TurnBudget;
import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.domain.Span;
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.SpanTypeEnum;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.api.repository.SessionRepository;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.core.concurrency.parallel.ParallelToolCallingManager;
import com.agentruntime.core.tool.DefaultToolFailureClassifier;
import com.agentruntime.core.tool.DefaultToolMetaRegistry;
import com.agentruntime.core.tool.ToolGovernanceDefaults;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 不显式传入具体模型的 ChatOptions 时，工具回调仍要进入模型看到的 Prompt。
 */
class AgentRuntimeToolOptionsTest {

    private static final String AGENT_NAME = "ToolAgent";

    private static final String AGENT_VERSION = "1.0.0";

    private static final String UID = "u-1";

    private static final String SESSION_ID = "s-1";

    private static final String USER_TEXT = "hello";

    private static final String TOOL_NAME = "lookup_status";

    private static final String SAMPLE_MODEL = "demo-model";

    private static final Double SAMPLE_TEMPERATURE = 0.7d;

    @Test
    void toolsReachModelWhenDefaultOptionsMissing() {
        Prompt prompt = run(null);

        ToolCallingChatOptions options = toolOptions(prompt);
        assertEquals(Boolean.FALSE, options.getInternalToolExecutionEnabled());
        assertTrue(hasTool(options));
    }

    @Test
    void toolsReachModelWhenDefaultOptionsAreGeneric() {
        ChatOptions requested = ChatOptions.builder()
                .model(SAMPLE_MODEL)
                .temperature(SAMPLE_TEMPERATURE)
                .build();

        Prompt prompt = run(requested);

        ToolCallingChatOptions options = toolOptions(prompt);
        assertEquals(Boolean.FALSE, options.getInternalToolExecutionEnabled());
        assertEquals(SAMPLE_MODEL, options.getModel());
        assertEquals(SAMPLE_TEMPERATURE, options.getTemperature());
        assertTrue(hasTool(options));
    }

    @Test
    void toolsReachModelWhenDefaultOptionsAlreadySupportTools() {
        DefaultToolCallingChatOptions requested = new DefaultToolCallingChatOptions();
        requested.setModel(SAMPLE_MODEL);
        requested.setTemperature(SAMPLE_TEMPERATURE);

        Prompt prompt = run(requested);

        ToolCallingChatOptions options = toolOptions(prompt);
        assertEquals(Boolean.FALSE, options.getInternalToolExecutionEnabled());
        assertEquals(SAMPLE_MODEL, options.getModel());
        assertEquals(SAMPLE_TEMPERATURE, options.getTemperature());
        assertTrue(hasTool(options));
    }

    private Prompt run(ChatOptions options) {
        CapturingChatModel model = new CapturingChatModel();
        AgentRuntimeBuilder builder = factory().builder(model)
                .agentName(AGENT_NAME)
                .agentVersion(AGENT_VERSION)
                .tools(new LookupTool());
        if (options != null) {
            builder.defaultOptions(options);
        }
        builder.build().call(UID, SESSION_ID, USER_TEXT);
        return model.prompt;
    }

    private AgentRuntimeFactory factory() {
        SessionRepository sessions = mock(SessionRepository.class);
        when(sessions.findById(any())).thenReturn(Optional.empty());
        when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new AgentRuntimeFactory(sessions, new NoopRecorder(), new ObjectMapper(),
                new DefaultToolMetaRegistry(), new DefaultToolFailureClassifier(), new ToolGovernanceDefaults(),
                new ZeroTokenEstimator(), null, Runnable::run, ParallelToolCallingManager.builder().build(),
                TurnBudget.defaults(), null);
    }

    private ToolCallingChatOptions toolOptions(Prompt prompt) {
        assertTrue(prompt.getOptions() instanceof ToolCallingChatOptions);
        return (ToolCallingChatOptions) prompt.getOptions();
    }

    private boolean hasTool(ToolCallingChatOptions options) {
        return options.getToolCallbacks().stream()
                .anyMatch(callback -> TOOL_NAME.equals(callback.getToolDefinition().name()));
    }

    /** 测试工具。模型不会真正调用它，只验证定义被放进请求。 */
    public static class LookupTool {

        /**
         * @param userId 用户 ID
         * @return 状态
         */
        @Tool(name = TOOL_NAME, description = "Looks up a status.")
        public String lookup(String userId) {
            return "ok";
        }
    }

    private static final class CapturingChatModel implements ChatModel {

        private Prompt prompt;

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt = prompt;
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return Flux.just(call(prompt));
        }
    }

    private static final class NoopRecorder implements ObservabilityRecorder {

        @Override
        public Span startSpan(SpanTypeEnum type, String parentSpanId, String sessionId) {
            Span span = new Span();
            span.setSpanId(type.getCode());
            span.setParentSpanId(parentSpanId);
            span.setSessionId(sessionId);
            span.setSpanType(type);
            return span;
        }

        @Override
        public void endSpan(String spanId) {
        }

        @Override
        public void recordEvent(String spanId, EventTypeEnum type, String payload) {
        }
    }

    private static final class ZeroTokenEstimator implements TokenEstimator {

        @Override
        public int estimate(String text) {
            return 0;
        }

        @Override
        public int estimateJson(String json) {
            return 0;
        }

        @Override
        public int estimateMessages(List<AgentMessage> messages) {
            return 0;
        }
    }
}
