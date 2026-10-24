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

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.domain.EventRecord;
import com.agentruntime.api.domain.Span;
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.SpanTypeEnum;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.api.repository.SessionRepository;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.capability.skill.SkillRuntime;
import com.agentruntime.capability.skill.SkillTurnPlan;
import com.agentruntime.runtime.hook.TurnHook;
import com.agentruntime.runtime.hook.TurnHookConstants;
import com.agentruntime.runtime.hook.TurnHookContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeTurnHookTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    private final TokenEstimator tokenEstimator = new ZeroTokenEstimator();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void callReturnsEarlyWhenPrepareHookInterrupts() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        SkillRuntime skillRuntime = mock(SkillRuntime.class);
        when(skillRuntime.prepare(nullable(String.class), nullable(String.class), any())).thenAnswer(invocation -> {
            release.await();
            finished.set(true);
            return SkillTurnPlan.passthrough(List.of());
        });
        ChatClient chatClient = mock(ChatClient.class);
        RecordingRecorder recorder = new RecordingRecorder();
        AgentRuntime runtime = runtime(chatClient, recorder, skillRuntime, new TurnHook() {
            @Override
            public void onPrepare(TurnHookContext context) {
                context.interrupt("SENSITIVE_WORD", "blocked");
            }
        });

        try {
            Future<String> pending = executor.submit(() -> runtime.call("u-1", "s-1", "hello"));
            assertEquals("blocked", pending.get(2, TimeUnit.SECONDS));
            assertFalse(finished.get());
        } finally {
            release.countDown();
        }

        verify(chatClient, never()).prompt();
        verify(skillRuntime, never()).begin(any(), any(), any());
        assertInterrupted(recorder, "PREPARE", "SENSITIVE_WORD", "blocked");
    }

    @Test
    void streamReturnsEarlyWhenBeforePromptInterrupts() throws Exception {
        SkillRuntime skillRuntime = mock(SkillRuntime.class);
        when(skillRuntime.prepare(nullable(String.class), nullable(String.class), any()))
                .thenReturn(SkillTurnPlan.passthrough(List.of()));
        ChatClient chatClient = mock(ChatClient.class);
        RecordingRecorder recorder = new RecordingRecorder();
        AgentRuntime runtime = runtime(chatClient, recorder, skillRuntime, new TurnHook() {
            @Override
            public void beforePrompt(TurnHookContext context) {
                context.interrupt("RISK_HIT", "risk blocked");
            }
        });

        List<String> chunks = runtime.stream("u-1", "s-2", "hello").collectList().block(Duration.ofSeconds(2));

        assertEquals(List.of("risk blocked"), chunks);
        verify(chatClient, never()).prompt();
        verify(skillRuntime, never()).begin(any(), any(), any());
        assertInterrupted(recorder, "BEFORE_PROMPT", "RISK_HIT", "risk blocked");
    }

    @Test
    void hookFailureClosesTurnSpan() {
        RecordingRecorder recorder = new RecordingRecorder();
        AgentRuntime runtime = runtime(mock(ChatClient.class), recorder, null, new TurnHook() {
            @Override
            public void onPrepare(TurnHookContext context) {
                throw new IllegalStateException("risk down");
            }
        });

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> runtime.call("u-1", "s-3", "hello"));

        assertEquals("risk down", failure.getMessage());
        assertTrue(recorder.events.stream().noneMatch(event -> event.getEventType() == EventTypeEnum.TURN_INTERRUPTED));
        assertTrue(recorder.events.stream().noneMatch(event -> event.getEventType() == EventTypeEnum.FINAL_REPLY));
        assertTrue(recorder.ended.contains(turnSpanId(recorder)));
    }

    private void assertInterrupted(RecordingRecorder recorder, String phase, String reason, String reply)
            throws Exception {
        String turnSpanId = turnSpanId(recorder);
        List<EventRecord> interrupted = recorder.events.stream()
                .filter(event -> event.getEventType() == EventTypeEnum.TURN_INTERRUPTED)
                .toList();
        assertEquals(1, interrupted.size());
        assertEquals(turnSpanId, interrupted.get(0).getSpanId());
        assertTrue(recorder.events.stream().noneMatch(event -> event.getEventType() == EventTypeEnum.FINAL_REPLY));
        assertTrue(recorder.ended.contains(turnSpanId));

        JsonNode payload = objectMapper.readTree(interrupted.get(0).getPayload());
        assertEquals(phase, payload.get(TurnHookConstants.PAYLOAD_PHASE).asText());
        assertEquals(reason, payload.get(TurnHookConstants.PAYLOAD_REASON).asText());
        assertEquals(reply, payload.get(TurnHookConstants.PAYLOAD_REPLY).asText());
    }

    private String turnSpanId(RecordingRecorder recorder) {
        return recorder.spans.stream()
                .filter(span -> span.getSpanType() == SpanTypeEnum.TURN)
                .map(Span::getSpanId)
                .findFirst()
                .orElseThrow();
    }

    private AgentRuntime runtime(ChatClient chatClient, RecordingRecorder recorder, SkillRuntime skillRuntime,
                                 TurnHook hook) {
        SessionRepository sessions = mock(SessionRepository.class);
        when(sessions.findById(any())).thenReturn(Optional.empty());
        when(sessions.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new AgentRuntime(chatClient, "DemoAgent", "1.0.0", null, null, sessions, recorder,
                List.of(), null, tokenEstimator, skillRuntime, List.of(hook), executor, objectMapper);
    }

    private static final class RecordingRecorder implements ObservabilityRecorder {

        private final List<Span> spans = new ArrayList<>();

        private final List<EventRecord> events = new ArrayList<>();

        private final List<String> ended = new ArrayList<>();

        @Override
        public Span startSpan(SpanTypeEnum type, String parentSpanId, String sessionId) {
            Span span = new Span();
            span.setSpanId(type.getCode() + "-" + spans.size());
            span.setParentSpanId(parentSpanId);
            span.setSessionId(sessionId);
            span.setSpanType(type);
            spans.add(span);
            return span;
        }

        @Override
        public void endSpan(String spanId) {
            ended.add(spanId);
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
