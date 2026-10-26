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
package com.agentruntime.runtime.turn;

import com.agentruntime.api.domain.TurnFrame;
import com.agentruntime.api.domain.TurnRecord;
import com.agentruntime.api.enums.TurnFrameTypeEnum;
import com.agentruntime.api.enums.TurnStatusEnum;
import com.agentruntime.api.exception.TurnBusyException;
import com.agentruntime.api.turn.TurnExecutionConstants;
import com.agentruntime.api.turn.TurnMessageDiscarder;
import com.agentruntime.autoconfigure.TurnExecutionProperties;
import com.agentruntime.memory.turn.InMemoryTurnStore;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnExecutionEngineTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-25T00:00:00Z"));

    private final InMemoryTurnStore store = new InMemoryTurnStore(clock);

    private final AtomicInteger discarded = new AtomicInteger();

    private TurnExecutionEngine engine;

    @AfterEach
    void shutdown() {
        if (engine != null) {
            engine.destroy();
        }
    }

    @Test
    void streamPublishesAnswerThenDone() {
        engine = engine(false);
        TurnHandle handle = engine.open("demo", "uid", "session-1", "hello",
                (userId, sessionId, query) -> Flux.just("hi"));

        List<TurnFrame> frames = handle.frames().collectList().block(TIMEOUT);

        assertEquals(2, frames.size());
        assertEquals(TurnFrameTypeEnum.ANSWER, frames.get(0).getType());
        assertEquals("hi", frames.get(0).getText());
        assertEquals(TurnFrameTypeEnum.DONE, frames.get(1).getType());
        assertEquals(TurnStatusEnum.COMPLETED, store.find(handle.getTurnId()).orElseThrow().getStatus());
    }

    @Test
    void secondOpenReusesTheRunningTurnAndCallIsBusy() {
        engine = engine(false);
        Sinks.Many<String> tokens = Sinks.many().unicast().onBackpressureBuffer();
        TurnHandle first = engine.open("demo", "uid", "session-1", "hello",
                (userId, sessionId, query) -> tokens.asFlux());
        tokens.tryEmitNext("partial");

        TurnHandle second = engine.open("demo", "uid", "session-1", "again",
                (userId, sessionId, query) -> Flux.just("should-not-run"));

        assertTrue(second.isReused());
        assertEquals(first.getTurnId(), second.getTurnId());
        assertThrows(TurnBusyException.class,
                () -> engine.call("demo", "uid", "session-1", "sync", () -> "no"));

        tokens.tryEmitComplete();
        List<TurnFrame> frames = second.frames().collectList().block(TIMEOUT);
        assertEquals(TurnFrameTypeEnum.DONE, frames.get(frames.size() - 1).getType());
    }

    @Test
    void disconnectCancelsOnlyWhenConfigured() {
        engine = engine(true);
        Sinks.Many<String> tokens = Sinks.many().unicast().onBackpressureBuffer();
        AtomicInteger cancelled = new AtomicInteger();
        TurnHandle handle = engine.open("demo", "uid", "session-1", "hello",
                (userId, sessionId, query) -> tokens.asFlux().doOnCancel(cancelled::incrementAndGet));
        tokens.tryEmitNext("partial");
        handle.frames().take(1).blockLast(TIMEOUT);

        await(() -> cancelled.get() > 0);
        await(() -> store.find(handle.getTurnId()).orElseThrow().getStatus() == TurnStatusEnum.CANCELLED);
        assertEquals(TurnExecutionConstants.ERROR_CLIENT_DISCONNECTED,
                store.find(handle.getTurnId()).orElseThrow().getErrorCode());
    }

    @Test
    void disconnectLeavesTheTurnRunningByDefault() {
        engine = engine(false);
        Sinks.Many<String> tokens = Sinks.many().unicast().onBackpressureBuffer();
        AtomicInteger cancelled = new AtomicInteger();
        TurnHandle handle = engine.open("demo", "uid", "session-1", "hello",
                (userId, sessionId, query) -> tokens.asFlux().doOnCancel(cancelled::incrementAndGet));
        tokens.tryEmitNext("partial");
        handle.frames().take(1).blockLast(TIMEOUT);

        await(() -> store.listEvents(handle.getTurnId(), 0L).stream()
                .anyMatch(frame -> frame.getType() == TurnFrameTypeEnum.ANSWER));
        assertEquals(0, cancelled.get());
        assertEquals(TurnStatusEnum.RUNNING, store.find(handle.getTurnId()).orElseThrow().getStatus());

        tokens.tryEmitComplete();
        engine.follow(handle.getTurnId(), 0L).collectList().block(TIMEOUT);
        assertEquals(TurnStatusEnum.COMPLETED, store.find(handle.getTurnId()).orElseThrow().getStatus());
    }

    @Test
    void expiredTurnRerunsAfterDiscardingItsMessages() {
        engine = engine(false);
        engine.register("demo", (userId, sessionId, query) -> Flux.just("rerun"));
        String turnId = openExpired("turn-1");

        engine.scanRecoverable();

        await(() -> store.find(turnId).orElseThrow().getStatus() == TurnStatusEnum.COMPLETED);
        assertEquals(1, discarded.get());
        List<TurnFrame> events = store.listEvents(turnId, 0L);
        assertEquals(TurnFrameTypeEnum.TURN_RESTARTED, events.get(0).getType());
        assertEquals(TurnFrameTypeEnum.ANSWER, events.get(1).getType());
        assertEquals("rerun", events.get(1).getText());
        assertEquals(TurnFrameTypeEnum.DONE, events.get(2).getType());
    }

    @Test
    void expiredTurnWithUnsafeToolDoesNotRerun() {
        engine = engine(false);
        AtomicInteger runs = new AtomicInteger();
        engine.register("demo", (userId, sessionId, query) -> {
            runs.incrementAndGet();
            return Flux.just("rerun");
        });
        String turnId = openExpired("turn-1");
        store.markUnsafeTool(turnId);

        engine.scanRecoverable();

        await(() -> store.find(turnId).orElseThrow().getStatus() == TurnStatusEnum.FAILED);
        assertEquals(0, runs.get());
        assertEquals(0, discarded.get());
        assertEquals(TurnExecutionConstants.ERROR_NON_IDEMPOTENT_PARTIAL,
                store.find(turnId).orElseThrow().getErrorCode());
    }

    private String openExpired(String turnId) {
        TurnRecord draft = new TurnRecord();
        draft.setTurnId(turnId);
        draft.setSessionId("session-1");
        draft.setUserId("uid");
        draft.setAgentName("demo");
        draft.setQuery("hello");
        store.begin(draft);
        store.markRunning(turnId, "old-owner", clock.instant().minusSeconds(1));
        return turnId;
    }

    private TurnExecutionEngine engine(boolean cancelOnDisconnect) {
        TurnExecutionProperties properties = new TurnExecutionProperties();
        properties.setCancelOnClientDisconnect(cancelOnDisconnect);
        properties.setEventBatchSize(1);
        properties.setEventBatchIntervalMillis(20L);
        properties.setLeaseTtlMillis(30_000L);
        return new TurnExecutionEngine(store, (sessionId, turnId) -> discarded.incrementAndGet(),
                properties, Executors.newVirtualThreadPerTaskExecutor(), clock);
    }

    private static void await(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertTrue(condition.getAsBoolean());
    }

    private static final class MutableClock extends Clock {

        private final Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
