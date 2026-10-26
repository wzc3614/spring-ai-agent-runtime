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
package com.agentruntime.memory.turn;

import com.agentruntime.api.domain.TurnBeginResult;
import com.agentruntime.api.domain.TurnFrame;
import com.agentruntime.api.domain.TurnRecord;
import com.agentruntime.api.enums.TurnFrameTypeEnum;
import com.agentruntime.api.enums.TurnStatusEnum;
import com.agentruntime.api.turn.TurnStore;

import org.apache.commons.lang3.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内轮次存储，供单测核对协议。自动配置不会注册它，重启即丢。
 */
public class InMemoryTurnStore implements TurnStore {

    private final Clock clock;

    private final Object monitor = new Object();

    private final Map<String, State> turns = new ConcurrentHashMap<>();

    private final Map<String, String> activeBySession = new HashMap<>();

    public InMemoryTurnStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public TurnBeginResult begin(TurnRecord draft) {
        synchronized (monitor) {
            String activeId = activeBySession.get(draft.getSessionId());
            if (activeId != null) {
                State active = turns.get(activeId);
                if (active != null && active.record.getStatus().isOpen()) {
                    return result(true, copy(active.record));
                }
                activeBySession.remove(draft.getSessionId());
            }
            Instant now = clock.instant();
            State state = new State();
            state.record = copy(draft);
            state.record.setStatus(TurnStatusEnum.ACCEPTED);
            state.record.setLeaseOwner(null);
            state.record.setLeaseDeadline(null);
            state.record.setErrorCode(null);
            state.record.setFinalAnswer(null);
            state.record.setCreatedAt(now);
            state.record.setUpdatedAt(now);
            turns.put(draft.getTurnId(), state);
            activeBySession.put(draft.getSessionId(), draft.getTurnId());
            return result(false, copy(state.record));
        }
    }

    @Override
    public void markRunning(String turnId, String leaseOwner, Instant leaseDeadline) {
        synchronized (monitor) {
            State state = require(turnId);
            TurnStatusEnum status = state.record.getStatus();
            if (!status.isOpen()) {
                throw new IllegalStateException("turn is not runnable, turnId=" + turnId);
            }
            if (status == TurnStatusEnum.RUNNING && !StringUtils.equals(state.record.getLeaseOwner(), leaseOwner)) {
                throw new IllegalStateException("turn lease owner mismatch, turnId=" + turnId);
            }
            state.record.setStatus(TurnStatusEnum.RUNNING);
            state.record.setLeaseOwner(leaseOwner);
            state.record.setLeaseDeadline(leaseDeadline);
            state.record.setUpdatedAt(clock.instant());
        }
    }

    @Override
    public boolean renewLease(String turnId, String leaseOwner, Instant leaseDeadline) {
        synchronized (monitor) {
            State state = turns.get(turnId);
            if (state == null || state.record.getStatus() != TurnStatusEnum.RUNNING) {
                return false;
            }
            if (!StringUtils.equals(state.record.getLeaseOwner(), leaseOwner)) {
                return false;
            }
            state.record.setLeaseDeadline(leaseDeadline);
            state.record.setUpdatedAt(clock.instant());
            return true;
        }
    }

    @Override
    public boolean tryClaimExpired(String turnId, String leaseOwner, Instant leaseDeadline, Instant acceptedBefore) {
        synchronized (monitor) {
            State state = turns.get(turnId);
            if (state == null || !claimable(state.record, clock.instant(), acceptedBefore)) {
                return false;
            }
            state.record.setStatus(TurnStatusEnum.RUNNING);
            state.record.setLeaseOwner(leaseOwner);
            state.record.setLeaseDeadline(leaseDeadline);
            state.record.setUpdatedAt(clock.instant());
            state.events.clear();
            state.nextSeq = 0L;
            appendFrame(state, TurnFrameTypeEnum.TURN_RESTARTED, turnId);
            return true;
        }
    }

    @Override
    public long appendEvent(String turnId, String leaseOwner, TurnFrameTypeEnum type, String text) {
        synchronized (monitor) {
            State state = turns.get(turnId);
            if (!ownedOpen(state, leaseOwner)) {
                return -1L;
            }
            return appendFrame(state, type, text);
        }
    }

    @Override
    public boolean complete(String turnId, String leaseOwner, String answer) {
        synchronized (monitor) {
            State state = turns.get(turnId);
            if (!ownedOpen(state, leaseOwner)) {
                return false;
            }
            finish(state, TurnStatusEnum.COMPLETED, null, answer);
            appendFrame(state, TurnFrameTypeEnum.DONE, "");
            return true;
        }
    }

    @Override
    public boolean fail(String turnId, String leaseOwner, String errorCode) {
        synchronized (monitor) {
            State state = turns.get(turnId);
            if (!ownedOpen(state, leaseOwner)) {
                return false;
            }
            finish(state, TurnStatusEnum.FAILED, errorCode, null);
            appendFrame(state, TurnFrameTypeEnum.ERROR, errorCode);
            return true;
        }
    }

    @Override
    public boolean failExpired(String turnId, String errorCode, Instant acceptedBefore) {
        synchronized (monitor) {
            State state = turns.get(turnId);
            if (state == null || !claimable(state.record, clock.instant(), acceptedBefore)) {
                return false;
            }
            finish(state, TurnStatusEnum.FAILED, errorCode, null);
            appendFrame(state, TurnFrameTypeEnum.ERROR, errorCode);
            return true;
        }
    }

    @Override
    public boolean cancel(String turnId, String leaseOwner, String errorCode) {
        synchronized (monitor) {
            State state = turns.get(turnId);
            if (!ownedOpen(state, leaseOwner)) {
                return false;
            }
            finish(state, TurnStatusEnum.CANCELLED, errorCode, null);
            appendFrame(state, TurnFrameTypeEnum.ERROR, errorCode);
            return true;
        }
    }

    @Override
    public Optional<TurnRecord> find(String turnId) {
        synchronized (monitor) {
            State state = turns.get(turnId);
            if (state == null) {
                return Optional.empty();
            }
            return Optional.of(copy(state.record));
        }
    }

    @Override
    public List<TurnRecord> listRecoverable(String agentName, Instant now, Instant acceptedBefore) {
        synchronized (monitor) {
            List<TurnRecord> recoverable = new ArrayList<>();
            for (State state : turns.values()) {
                if (StringUtils.equals(agentName, state.record.getAgentName())
                        && claimable(state.record, now, acceptedBefore)) {
                    recoverable.add(copy(state.record));
                }
            }
            return recoverable;
        }
    }

    @Override
    public List<TurnFrame> listEvents(String turnId, long afterSeq) {
        synchronized (monitor) {
            State state = turns.get(turnId);
            if (state == null) {
                return List.of();
            }
            List<TurnFrame> frames = new ArrayList<>();
            for (TurnFrame frame : state.events) {
                if (frame.getSeq() > afterSeq) {
                    frames.add(copyFrame(frame));
                }
            }
            return frames;
        }
    }

    @Override
    public void markUnsafeTool(String turnId) {
        synchronized (monitor) {
            State state = turns.get(turnId);
            if (state != null) {
                state.unsafeTool = true;
            }
        }
    }

    @Override
    public boolean hasUnsafeTool(String turnId) {
        synchronized (monitor) {
            State state = turns.get(turnId);
            return state != null && state.unsafeTool;
        }
    }

    private State require(String turnId) {
        State state = turns.get(turnId);
        if (state == null) {
            throw new IllegalStateException("turn not found, turnId=" + turnId);
        }
        return state;
    }

    private boolean ownedOpen(State state, String leaseOwner) {
        return state != null
                && state.record.getStatus().isOpen()
                && StringUtils.equals(state.record.getLeaseOwner(), leaseOwner);
    }

    private boolean claimable(TurnRecord record, Instant now, Instant acceptedBefore) {
        if (record.getStatus() == TurnStatusEnum.RUNNING) {
            Instant deadline = record.getLeaseDeadline();
            return deadline == null || !deadline.isAfter(now);
        }
        if (record.getStatus() == TurnStatusEnum.ACCEPTED) {
            Instant createdAt = record.getCreatedAt();
            return createdAt != null && !createdAt.isAfter(acceptedBefore);
        }
        return false;
    }

    private void finish(State state, TurnStatusEnum status, String errorCode, String answer) {
        state.record.setStatus(status);
        state.record.setErrorCode(errorCode);
        if (answer != null) {
            state.record.setFinalAnswer(answer);
        }
        state.record.setLeaseOwner(null);
        state.record.setLeaseDeadline(null);
        state.record.setUpdatedAt(clock.instant());
        String activeId = activeBySession.get(state.record.getSessionId());
        if (state.record.getTurnId().equals(activeId)) {
            activeBySession.remove(state.record.getSessionId());
        }
    }

    private long appendFrame(State state, TurnFrameTypeEnum type, String text) {
        state.nextSeq = state.nextSeq + 1L;
        TurnFrame frame = new TurnFrame();
        frame.setTurnId(state.record.getTurnId());
        frame.setSeq(state.nextSeq);
        frame.setType(type);
        frame.setText(text == null ? "" : text);
        state.events.add(frame);
        return frame.getSeq();
    }

    private static TurnBeginResult result(boolean reused, TurnRecord turn) {
        TurnBeginResult result = new TurnBeginResult();
        result.setReused(reused);
        result.setTurn(turn);
        return result;
    }

    static TurnRecord copy(TurnRecord source) {
        TurnRecord copy = new TurnRecord();
        copy.setTurnId(source.getTurnId());
        copy.setSessionId(source.getSessionId());
        copy.setUserId(source.getUserId());
        copy.setAgentName(source.getAgentName());
        copy.setQuery(source.getQuery());
        copy.setStatus(source.getStatus());
        copy.setLeaseOwner(source.getLeaseOwner());
        copy.setLeaseDeadline(source.getLeaseDeadline());
        copy.setErrorCode(source.getErrorCode());
        copy.setFinalAnswer(source.getFinalAnswer());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private static TurnFrame copyFrame(TurnFrame source) {
        TurnFrame copy = new TurnFrame();
        copy.setTurnId(source.getTurnId());
        copy.setSeq(source.getSeq());
        copy.setType(source.getType());
        copy.setText(source.getText());
        return copy;
    }

    private static final class State {

        private TurnRecord record;

        private final List<TurnFrame> events = new ArrayList<>();

        private long nextSeq;

        private boolean unsafeTool;
    }
}
