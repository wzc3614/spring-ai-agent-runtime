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

import com.agentruntime.api.enums.TurnFrameTypeEnum;
import com.agentruntime.api.turn.TurnStore;
import com.agentruntime.autoconfigure.TurnExecutionProperties;

/**
 * 把回答 token 合成少量回放帧。持有者对不上时通知外层停掉这一轮。
 */
class TurnEventBatcher {

    private final TurnStore store;

    private final String turnId;

    private final String leaseOwner;

    private final int batchSize;

    private final long batchIntervalMillis;

    private final java.time.Clock clock;

    private final StringBuilder pending = new StringBuilder();

    private final StringBuilder full = new StringBuilder();

    private int count;

    private long lastFlushMillis;

    private boolean lostLease;

    TurnEventBatcher(TurnStore store, String turnId, String leaseOwner, TurnExecutionProperties properties,
                     java.time.Clock clock) {
        this.store = store;
        this.turnId = turnId;
        this.leaseOwner = leaseOwner;
        this.batchSize = properties.getEventBatchSize();
        this.batchIntervalMillis = properties.getEventBatchIntervalMillis();
        this.clock = clock;
        this.lastFlushMillis = clock.millis();
    }

    synchronized void append(String token) {
        if (token == null || token.isEmpty() || lostLease) {
            return;
        }
        pending.append(token);
        full.append(token);
        count++;
        long now = clock.millis();
        if (count >= batchSize || now - lastFlushMillis >= batchIntervalMillis) {
            flushLocked();
        }
    }

    synchronized void flush() {
        flushLocked();
    }

    synchronized String fullText() {
        return full.toString();
    }

    synchronized boolean isLostLease() {
        return lostLease;
    }

    private void flushLocked() {
        lastFlushMillis = clock.millis();
        if (pending.isEmpty() || lostLease) {
            return;
        }
        String text = pending.toString();
        pending.setLength(0);
        count = 0;
        long seq = store.appendEvent(turnId, leaseOwner, TurnFrameTypeEnum.ANSWER, text);
        if (seq < 0L) {
            lostLease = true;
        }
    }
}
