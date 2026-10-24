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
package com.agentruntime.runtime.hook;

import com.agentruntime.capability.skill.SkillTurnPlan;
import com.agentruntime.core.springai.SpanContextHolder;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnHookGateTest {

    private final TurnHookGate gate = new TurnHookGate();

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        SpanContextHolder.clear();
        executor.shutdownNow();
    }

    @Test
    void prepareStaysOnCallerWhenNoHook() {
        AtomicReference<Thread> thread = new AtomicReference<>();
        TurnHookResult result = gate.open("u", "s", "hello", () -> {
            thread.set(Thread.currentThread());
            return SkillTurnPlan.passthrough(List.of());
        }, List.of(), executor);

        assertFalse(result.isInterrupted());
        assertSame(Thread.currentThread(), thread.get());
    }

    @Test
    void onPrepareInterruptDoesNotJoinPrepareOrCallBeforePrompt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean beforePrompt = new AtomicBoolean(false);
        try {
            Future<TurnHookResult> pending = executor.submit(() -> gate.open("u", "s", "hello", () -> {
                await(release);
                finished.set(true);
                return SkillTurnPlan.passthrough(List.of());
            }, List.of(new TurnHook() {
                @Override
                public void onPrepare(TurnHookContext context) {
                    context.interrupt("SENSITIVE_WORD", "blocked");
                }

                @Override
                public void beforePrompt(TurnHookContext context) {
                    beforePrompt.set(true);
                }
            }), executor));

            TurnHookResult result = pending.get(2, TimeUnit.SECONDS);
            assertTrue(result.isInterrupted());
            assertEquals(TurnHookPhaseEnum.PREPARE, result.getPhase());
            assertEquals("SENSITIVE_WORD", result.getReasonCode());
            assertEquals("blocked", result.getReply());
            assertFalse(finished.get());
            assertFalse(beforePrompt.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void interruptFromAnotherThreadSkipsBeforePrompt() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean beforePrompt = new AtomicBoolean(false);
        try {
            Future<TurnHookResult> pending = executor.submit(() -> gate.open("u", "s", "hello", () -> {
                await(release);
                return SkillTurnPlan.passthrough(List.of());
            }, List.of(new TurnHook() {
                @Override
                public void onPrepare(TurnHookContext context) {
                    Thread.startVirtualThread(() -> context.interrupt("RISK_HIT", "risk blocked"));
                }

                @Override
                public void beforePrompt(TurnHookContext context) {
                    beforePrompt.set(true);
                }
            }), executor));

            TurnHookResult result = pending.get(2, TimeUnit.SECONDS);
            assertTrue(result.isInterrupted());
            assertEquals(TurnHookPhaseEnum.PREPARE, result.getPhase());
            assertEquals("risk blocked", result.getReply());
            assertFalse(beforePrompt.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void beforePromptSeesPlanAndCanInterrupt() {
        SkillTurnPlan plan = SkillTurnPlan.passthrough(List.of());
        AtomicReference<SkillTurnPlan> seen = new AtomicReference<>();
        TurnHookResult result = gate.open("u", "s", "hello", () -> plan, List.of(new TurnHook() {
            @Override
            public void onPrepare(TurnHookContext context) {
                assertNull(context.getPlan());
            }

            @Override
            public void beforePrompt(TurnHookContext context) {
                seen.set(context.getPlan());
                context.interrupt("RISK_HIT", "risk blocked");
            }
        }), executor);

        assertSame(plan, seen.get());
        assertTrue(result.isInterrupted());
        assertEquals(TurnHookPhaseEnum.BEFORE_PROMPT, result.getPhase());
        assertNull(result.getPlan());
    }

    @Test
    void laterHookIsSkippedAfterInterrupt() {
        AtomicBoolean second = new AtomicBoolean(false);
        TurnHookResult result = gate.open("u", "s", "hello", () -> SkillTurnPlan.passthrough(List.of()),
                List.of(interrupting("SENSITIVE_WORD", "blocked"), new TurnHook() {
                    @Override
                    public void onPrepare(TurnHookContext context) {
                        second.set(true);
                    }

                    @Override
                    public void beforePrompt(TurnHookContext context) {
                        second.set(true);
                    }
                }), executor);

        assertTrue(result.isInterrupted());
        assertFalse(second.get());
    }

    @Test
    void rejectedExecutorRunsPrepareOnCaller() {
        AtomicReference<Thread> thread = new AtomicReference<>();
        ExecutorService rejecting = new RejectingExecutor();
        TurnHookResult result = gate.open("u", "s", "hello", () -> {
            thread.set(Thread.currentThread());
            return SkillTurnPlan.passthrough(List.of());
        }, List.of(new TurnHook() {
        }), rejecting);

        assertFalse(result.isInterrupted());
        assertSame(Thread.currentThread(), thread.get());
    }

    @Test
    void prepareFailurePropagatesWhenNotInterrupted() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> gate.open("u", "s", "hello", () -> {
                    throw new IllegalStateException("prepare failed");
                }, List.of(new TurnHook() {
                }), executor));
        assertEquals("prepare failed", failure.getMessage());
    }

    @Test
    void workerInheritsSpanContextWithoutClearingCaller() {
        SpanContextHolder.set("turn-1", "session-1");
        AtomicReference<String> seen = new AtomicReference<>();
        gate.open("u", "s", "hello", () -> {
            seen.set(SpanContextHolder.parentSpanId());
            return SkillTurnPlan.passthrough(List.of());
        }, List.of(new TurnHook() {
        }), executor);

        assertEquals("turn-1", seen.get());
        assertEquals("turn-1", SpanContextHolder.parentSpanId());
        assertEquals("session-1", SpanContextHolder.sessionId());
    }

    @Test
    void firstInterruptWinsAndLateInterruptIsIgnored() {
        TurnHookContext context = new TurnHookContext();
        context.interrupt("EARLY", "first");
        context.interrupt("LATE", "second");
        assertEquals("EARLY", context.getReasonCode());
        assertEquals("first", context.getReply());
        assertFalse(context.seal());

        TurnHookContext sealed = new TurnHookContext();
        assertTrue(sealed.seal());
        sealed.interrupt("AFTER", "too late");
        assertFalse(sealed.isInterrupted());
    }

    @Test
    void blankInterruptIsRejected() {
        TurnHookContext context = new TurnHookContext();
        assertThrows(IllegalArgumentException.class, () -> context.interrupt(" ", "reply"));
        assertThrows(IllegalArgumentException.class, () -> context.interrupt("REASON", " "));
        assertFalse(context.isInterrupted());
    }

    private TurnHook interrupting(String reasonCode, String reply) {
        return new TurnHook() {
            @Override
            public void onPrepare(TurnHookContext context) {
                context.interrupt(reasonCode, reply);
            }
        };
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /**
     * 提交即拒绝，用来覆盖执行器满时退回调用线程的路径。
     */
    private static final class RejectingExecutor extends java.util.concurrent.AbstractExecutorService {

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("full");
        }
    }
}
