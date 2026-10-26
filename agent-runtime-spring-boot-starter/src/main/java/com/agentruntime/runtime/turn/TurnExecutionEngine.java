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

import com.agentruntime.api.domain.TurnBeginResult;
import com.agentruntime.api.domain.TurnFrame;
import com.agentruntime.api.domain.TurnRecord;
import com.agentruntime.api.enums.TurnFrameTypeEnum;
import com.agentruntime.api.enums.TurnStatusEnum;
import com.agentruntime.api.exception.TurnAccessException;
import com.agentruntime.api.exception.TurnBusyException;
import com.agentruntime.api.turn.TurnExecutionConstants;
import com.agentruntime.api.turn.TurnMessageDiscarder;
import com.agentruntime.api.turn.TurnStore;
import com.agentruntime.autoconfigure.TurnExecutionProperties;
import com.agentruntime.core.springai.TurnContextHolder;
import com.agentruntime.core.springai.TurnLeaseHooks;
import com.agentruntime.core.springai.TurnToolStartHook;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.annotation.Scheduled;

import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.SignalType;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * 轮次执行：单飞、脱离订阅者的模型调用、回放跟随、租约和整轮重跑。
 */
public class TurnExecutionEngine implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TurnExecutionEngine.class);

    private final TurnStore store;

    private final TurnMessageDiscarder discarder;

    private final TurnExecutionProperties properties;

    private final Executor worker;

    private final Clock clock;

    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
            .name("agent-runtime-turn-flush")
            .factory());

    private final Map<String, TurnModelRunner> runners = new ConcurrentHashMap<>();

    private final Map<String, LocalTurn> localTurns = new ConcurrentHashMap<>();

    public TurnExecutionEngine(TurnStore store, TurnMessageDiscarder discarder, TurnExecutionProperties properties,
                               Executor worker, Clock clock) {
        this.store = store;
        this.discarder = discarder;
        this.properties = properties;
        this.worker = worker;
        this.clock = clock;
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 注册这个 agent 的重跑入口。同名后者覆盖前者。
     *
     * @param agentName agent 名称
     * @param runner    模型调用
     */
    public void register(String agentName, TurnModelRunner runner) {
        String name = agentName == null ? "" : agentName;
        if (runners.put(name, runner) != null) {
            log.warn("turn runner replaced, agentName={}", name);
        }
    }

    /**
     * 同步一轮。已有未完成轮时抛 {@link TurnBusyException}。
     *
     * @param agentName agent 名称
     * @param userId    用户
     * @param sessionId 会话
     * @param query     用户原文
     * @param invoke    已经包在轮次上下文里的同步调用
     * @return 回答
     */
    public String call(String agentName, String userId, String sessionId, String query, Supplier<String> invoke) {
        TurnBeginResult begin = begin(agentName, userId, sessionId, query);
        if (begin.isReused()) {
            throw new TurnBusyException(begin.getTurn().getTurnId());
        }
        String owner = newOwner();
        store.markRunning(begin.getTurn().getTurnId(), owner, deadline());
        LocalTurn local = track(begin.getTurn(), owner);
        bindHooks(local);
        try {
            String reply = invoke.get();
            if (local.lostLease) {
                throw new TurnAccessException(TurnExecutionConstants.ERROR_LEASE_LOST, "turn lease lost");
            }
            writeAnswer(local, reply);
            if (!store.complete(local.turnId, owner, reply == null ? "" : reply)) {
                throw new TurnAccessException(TurnExecutionConstants.ERROR_LEASE_LOST, "turn lease lost");
            }
            return reply;
        } catch (TurnBusyException | TurnAccessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            failOwned(local, TurnExecutionConstants.ERROR_GRAPH_FAILED);
            throw ex;
        } finally {
            clearHooks();
            localTurns.remove(local.turnId, local);
        }
    }

    /**
     * 打开一轮流式执行。复用时只跟随已有事件。
     *
     * @param agentName agent 名称
     * @param userId    用户
     * @param sessionId 会话
     * @param query     用户原文
     * @param runner    模型调用
     * @return 句柄
     */
    public TurnHandle open(String agentName, String userId, String sessionId, String query, TurnModelRunner runner) {
        TurnBeginResult begin = begin(agentName, userId, sessionId, query);
        if (begin.isReused()) {
            return new TurnHandle(begin.getTurn().getTurnId(), true, follow(begin.getTurn().getTurnId(), 0L));
        }
        String owner = newOwner();
        store.markRunning(begin.getTurn().getTurnId(), owner, deadline());
        LocalTurn local = track(begin.getTurn(), owner);
        worker.execute(() -> runStream(local, () -> runner.open(userId, sessionId, query)));
        return new TurnHandle(local.turnId, false, follow(local.turnId, 0L));
    }

    /**
     * 从 afterSeq 之后跟随，直到 DONE / ERROR 或订阅取消。
     *
     * @param turnId   轮次
     * @param afterSeq 游标，不含
     * @return 帧流
     */
    public Flux<TurnFrame> follow(String turnId, long afterSeq) {
        return Flux.create(sink -> startFollow(sink, turnId, afterSeq));
    }

    /**
     * 给本进程正在跑的轮次续租约。丢了租约就停掉本地执行。
     */
    @Scheduled(fixedDelayString = "${agent.runtime.turn-execution.renew-interval-ms:10000}")
    public void renewLocalLeases() {
        if (!properties.isEnabled()) {
            return;
        }
        Instant nextDeadline = deadline();
        for (LocalTurn local : localTurns.values()) {
            if (store.renewLease(local.turnId, local.owner, nextDeadline)) {
                continue;
            }
            local.lostLease = true;
            dispose(local);
        }
    }

    /**
     * 认领过期轮次。非幂等工具已经开始的，失败关闭。
     */
    @Scheduled(fixedDelayString = "${agent.runtime.turn-execution.recovery-interval-ms:15000}")
    public void scanRecoverable() {
        if (!properties.isEnabled() || !properties.isCrashRecoveryEnabled()) {
            return;
        }
        Instant now = clock.instant();
        Instant acceptedBefore = now.minusMillis(properties.getLeaseTtlMillis());
        for (Map.Entry<String, TurnModelRunner> entry : runners.entrySet()) {
            List<TurnRecord> expired = store.listRecoverable(entry.getKey(), now, acceptedBefore);
            for (TurnRecord turn : expired) {
                worker.execute(() -> recover(turn, entry.getValue(), acceptedBefore));
            }
        }
    }

    @Override
    public void destroy() {
        flusher.shutdownNow();
    }

    private void recover(TurnRecord turn, TurnModelRunner runner, Instant acceptedBefore) {
        if (store.hasUnsafeTool(turn.getTurnId())) {
            store.failExpired(turn.getTurnId(), TurnExecutionConstants.ERROR_NON_IDEMPOTENT_PARTIAL, acceptedBefore);
            log.info("skip recovery, non-idempotent tool started, turnId={}", turn.getTurnId());
            return;
        }
        String owner = newOwner();
        if (!store.tryClaimExpired(turn.getTurnId(), owner, deadline(), acceptedBefore)) {
            log.info("skip recovery, turn already claimed or finished, turnId={}", turn.getTurnId());
            return;
        }
        try {
            discarder.discard(turn.getSessionId(), turn.getTurnId());
        } catch (RuntimeException ex) {
            log.error("discard turn messages failed, turnId={}", turn.getTurnId(), ex);
            store.fail(turn.getTurnId(), owner, TurnExecutionConstants.ERROR_GRAPH_FAILED);
            return;
        }
        log.info("recovering expired turn, turnId={} sessionId={}", turn.getTurnId(), turn.getSessionId());
        LocalTurn local = track(turn, owner);
        runStream(local, () -> runner.open(turn.getUserId(), turn.getSessionId(), turn.getQuery()));
    }

    private void runStream(LocalTurn local, Supplier<Flux<String>> fluxSupplier) {
        bindHooks(local);
        TurnEventBatcher batcher = new TurnEventBatcher(store, local.turnId, local.owner, properties, clock);
        ScheduledFuture<?> flushTask = flusher.scheduleAtFixedRate(batcher::flush,
                properties.getEventBatchIntervalMillis(), properties.getEventBatchIntervalMillis(), TimeUnit.MILLISECONDS);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        try {
            Disposable disposable = fluxSupplier.get()
                    .doOnNext(token -> onToken(local, batcher, token))
                    .doOnError(error::set)
                    .doFinally(signal -> {
                        try {
                            finishStream(local, batcher, signal, error.get());
                        } finally {
                            done.countDown();
                        }
                    })
                    .subscribe();
            local.subscription = disposable;
            if (local.cancelledByClient) {
                disposable.dispose();
            }
            done.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            finishStream(local, batcher, SignalType.CANCEL, ex);
        } catch (RuntimeException ex) {
            finishStream(local, batcher, SignalType.ON_ERROR, ex);
        } finally {
            flushTask.cancel(false);
            clearHooks();
            localTurns.remove(local.turnId, local);
        }
    }

    private void onToken(LocalTurn local, TurnEventBatcher batcher, String token) {
        if (local.lostLease || batcher.isLostLease()) {
            local.lostLease = true;
            dispose(local);
            return;
        }
        batcher.append(token);
        if (batcher.isLostLease()) {
            local.lostLease = true;
            dispose(local);
        }
    }

    private void finishStream(LocalTurn local, TurnEventBatcher batcher, SignalType signal, Throwable error) {
        if (!local.finished.compareAndSet(false, true)) {
            return;
        }
        batcher.flush();
        if (local.lostLease || batcher.isLostLease()) {
            log.info("turn stopped after lease loss, turnId={}", local.turnId);
            return;
        }
        if (signal == SignalType.ON_COMPLETE) {
            store.complete(local.turnId, local.owner, batcher.fullText());
            return;
        }
        if (local.cancelledByClient || signal == SignalType.CANCEL) {
            store.cancel(local.turnId, local.owner, TurnExecutionConstants.ERROR_CLIENT_DISCONNECTED);
            return;
        }
        log.error("turn execution failed, turnId={}", local.turnId, error);
        failOwned(local, TurnExecutionConstants.ERROR_GRAPH_FAILED);
    }

    private void startFollow(FluxSink<TurnFrame> sink, String turnId, long afterSeq) {
        LocalTurn local = localTurns.get(turnId);
        if (local != null) {
            local.subscribers.incrementAndGet();
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean released = new AtomicBoolean();
        worker.execute(() -> pump(sink, turnId, afterSeq, cancelled));
        sink.onCancel(() -> {
            cancelled.set(true);
            if (released.compareAndSet(false, true)) {
                releaseSubscriber(turnId);
            }
        });
    }

    private void pump(FluxSink<TurnFrame> sink, String turnId, long afterSeq, AtomicBoolean cancelled) {
        long cursor = afterSeq;
        boolean sawAnswer = false;
        try {
            while (!cancelled.get() && !sink.isCancelled()) {
                EmitCursor emitted = emit(sink, store.listEvents(turnId, cursor), cursor, sawAnswer);
                cursor = emitted.cursor;
                sawAnswer = emitted.sawAnswer;
                if (emitted.terminal) {
                    sink.complete();
                    return;
                }
                Optional<TurnRecord> found = store.find(turnId);
                if (found.isEmpty()) {
                    sink.error(new TurnAccessException(TurnExecutionConstants.ERROR_TURN_NOT_FOUND, "turn not found"));
                    return;
                }
                TurnRecord turn = found.get();
                if (!turn.getStatus().isOpen()) {
                    EmitCursor rest = emit(sink, store.listEvents(turnId, cursor), cursor, sawAnswer);
                    if (!rest.terminal) {
                        emitSynthetic(sink, turn, rest.cursor, rest.sawAnswer);
                    }
                    sink.complete();
                    return;
                }
                if (!sleep()) {
                    return;
                }
            }
        } catch (RuntimeException ex) {
            log.warn("turn follow stopped, turnId={}", turnId, ex);
        }
    }

    private EmitCursor emit(FluxSink<TurnFrame> sink, List<TurnFrame> frames, long cursor, boolean sawAnswer) {
        boolean terminal = false;
        for (TurnFrame frame : frames) {
            sink.next(frame);
            cursor = frame.getSeq();
            if (frame.getType() == TurnFrameTypeEnum.ANSWER) {
                sawAnswer = true;
            }
            if (frame.getType().isTerminal()) {
                terminal = true;
                break;
            }
        }
        return new EmitCursor(cursor, sawAnswer, terminal);
    }

    private void emitSynthetic(FluxSink<TurnFrame> sink, TurnRecord turn, long cursor, boolean sawAnswer) {
        long seq = cursor;
        if (turn.getStatus() == TurnStatusEnum.COMPLETED && !sawAnswer && StringUtils.isNotBlank(turn.getFinalAnswer())) {
            seq++;
            sink.next(frame(turn.getTurnId(), seq, TurnFrameTypeEnum.ANSWER, turn.getFinalAnswer()));
        }
        seq++;
        if (turn.getStatus() == TurnStatusEnum.COMPLETED) {
            sink.next(frame(turn.getTurnId(), seq, TurnFrameTypeEnum.DONE, ""));
            return;
        }
        sink.next(frame(turn.getTurnId(), seq, TurnFrameTypeEnum.ERROR,
                StringUtils.defaultString(turn.getErrorCode(), TurnExecutionConstants.ERROR_GRAPH_FAILED)));
    }

    private void releaseSubscriber(String turnId) {
        LocalTurn local = localTurns.get(turnId);
        if (local == null) {
            return;
        }
        if (local.subscribers.decrementAndGet() > 0 || !properties.isCancelOnClientDisconnect()) {
            return;
        }
        local.cancelledByClient = true;
        dispose(local);
    }

    private void writeAnswer(LocalTurn local, String reply) {
        if (StringUtils.isBlank(reply)) {
            return;
        }
        long seq = store.appendEvent(local.turnId, local.owner, TurnFrameTypeEnum.ANSWER, reply);
        if (seq < 0L) {
            local.lostLease = true;
        }
    }

    private void failOwned(LocalTurn local, String errorCode) {
        if (!local.lostLease) {
            store.fail(local.turnId, local.owner, errorCode);
        }
    }

    private void bindHooks(LocalTurn local) {
        TurnContextHolder.set(local.turnId);
        TurnLeaseHooks.set(() -> {
            if (!store.renewLease(local.turnId, local.owner, deadline())) {
                local.lostLease = true;
            }
        });
        TurnToolStartHook.set(idempotent -> {
            if (!idempotent) {
                store.markUnsafeTool(local.turnId);
            }
        });
    }

    private void clearHooks() {
        TurnToolStartHook.clear();
        TurnLeaseHooks.clear();
        TurnContextHolder.clear();
    }

    private TurnBeginResult begin(String agentName, String userId, String sessionId, String query) {
        TurnRecord draft = new TurnRecord();
        draft.setTurnId(UUID.randomUUID().toString());
        draft.setSessionId(sessionId);
        draft.setUserId(userId);
        draft.setAgentName(agentName == null ? "" : agentName);
        draft.setQuery(query);
        return store.begin(draft);
    }

    private LocalTurn track(TurnRecord turn, String owner) {
        LocalTurn local = new LocalTurn();
        local.turnId = turn.getTurnId();
        local.owner = owner;
        localTurns.put(turn.getTurnId(), local);
        return local;
    }

    private Instant deadline() {
        return clock.instant().plusMillis(properties.getLeaseTtlMillis());
    }

    private static String newOwner() {
        return UUID.randomUUID().toString();
    }

    private static void dispose(LocalTurn local) {
        Disposable subscription = local.subscription;
        if (subscription != null) {
            subscription.dispose();
        }
    }

    private static boolean sleep() {
        try {
            Thread.sleep(TurnExecutionConstants.EVENT_POLL_INTERVAL_MILLIS);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static TurnFrame frame(String turnId, long seq, TurnFrameTypeEnum type, String text) {
        TurnFrame frame = new TurnFrame();
        frame.setTurnId(turnId);
        frame.setSeq(seq);
        frame.setType(type);
        frame.setText(text);
        return frame;
    }

    private static final class LocalTurn {

        private String turnId;

        private String owner;

        private final AtomicInteger subscribers = new AtomicInteger();

        private final AtomicBoolean finished = new AtomicBoolean();

        private volatile Disposable subscription;

        private volatile boolean lostLease;

        private volatile boolean cancelledByClient;
    }

    private static final class EmitCursor {

        private final long cursor;

        private final boolean sawAnswer;

        private final boolean terminal;

        private EmitCursor(long cursor, boolean sawAnswer, boolean terminal) {
            this.cursor = cursor;
            this.sawAnswer = sawAnswer;
            this.terminal = terminal;
        }
    }
}
