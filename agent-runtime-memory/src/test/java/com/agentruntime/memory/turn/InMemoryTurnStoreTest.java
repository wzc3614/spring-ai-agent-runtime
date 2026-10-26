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

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryTurnStoreTest {

    private final Instant start = Instant.parse("2026-10-25T00:00:00Z");

    private final MutableClock clock = new MutableClock(start);

    private final InMemoryTurnStore store = new InMemoryTurnStore(clock);

    @Test
    void beginReusesTheOpenTurnOfTheSameSession() {
        TurnBeginResult first = store.begin(draft("turn-1", "session-1"));
        TurnBeginResult second = store.begin(draft("turn-2", "session-1"));

        assertFalse(first.isReused());
        assertTrue(second.isReused());
        assertEquals("turn-1", second.getTurn().getTurnId());
        assertEquals(TurnStatusEnum.ACCEPTED, second.getTurn().getStatus());
    }

    @Test
    void completeReleasesTheSessionSoTheNextBeginIsNew() {
        TurnBeginResult first = store.begin(draft("turn-1", "session-1"));
        store.markRunning("turn-1", "owner", start.plusSeconds(30));
        assertTrue(store.complete("turn-1", "owner", "done"));

        TurnBeginResult second = store.begin(draft("turn-2", "session-1"));
        assertFalse(second.isReused());
        assertEquals("turn-2", second.getTurn().getTurnId());
        assertEquals("done", store.find(first.getTurn().getTurnId()).orElseThrow().getFinalAnswer());
    }

    @Test
    void wrongOwnerCannotRenewOrComplete() {
        store.begin(draft("turn-1", "session-1"));
        store.markRunning("turn-1", "owner", start.plusSeconds(30));

        assertFalse(store.renewLease("turn-1", "other", start.plusSeconds(60)));
        assertFalse(store.complete("turn-1", "other", "nope"));
        assertEquals(TurnStatusEnum.RUNNING, store.find("turn-1").orElseThrow().getStatus());
    }

    @Test
    void acceptedTurnIsNotClaimableUntilItIsOlderThanTheGraceWindow() {
        store.begin(draft("turn-1", "session-1"));

        assertFalse(store.tryClaimExpired("turn-1", "new-owner", start.plusSeconds(30), start.minusSeconds(1)));
        assertTrue(store.tryClaimExpired("turn-1", "new-owner", start.plusSeconds(30), start));

        List<TurnFrame> events = store.listEvents("turn-1", 0L);
        assertEquals(1, events.size());
        assertEquals(TurnFrameTypeEnum.TURN_RESTARTED, events.get(0).getType());
        assertEquals("turn-1", events.get(0).getText());
        assertEquals("new-owner", store.find("turn-1").orElseThrow().getLeaseOwner());
    }

    @Test
    void claimClearsEventsWrittenByThePreviousOwner() {
        store.begin(draft("turn-1", "session-1"));
        store.markRunning("turn-1", "owner", start.minusSeconds(1));
        store.appendEvent("turn-1", "owner", TurnFrameTypeEnum.ANSWER, "partial");

        assertTrue(store.tryClaimExpired("turn-1", "new-owner", start.plusSeconds(30), start));

        List<TurnFrame> events = store.listEvents("turn-1", 0L);
        assertEquals(1, events.size());
        assertEquals(TurnFrameTypeEnum.TURN_RESTARTED, events.get(0).getType());
    }

    @Test
    void expiredTurnWithUnsafeToolFailsWithoutANewOwner() {
        store.begin(draft("turn-1", "session-1"));
        store.markRunning("turn-1", "owner", start.minusSeconds(1));
        store.markUnsafeTool("turn-1");

        assertTrue(store.failExpired("turn-1", "NON_IDEMPOTENT_PARTIAL", start));

        TurnRecord failed = store.find("turn-1").orElseThrow();
        assertEquals(TurnStatusEnum.FAILED, failed.getStatus());
        assertEquals("NON_IDEMPOTENT_PARTIAL", failed.getErrorCode());
        assertEquals(TurnFrameTypeEnum.ERROR, store.listEvents("turn-1", 0L).get(0).getType());
    }

    private static TurnRecord draft(String turnId, String sessionId) {
        TurnRecord draft = new TurnRecord();
        draft.setTurnId(turnId);
        draft.setSessionId(sessionId);
        draft.setUserId("uid");
        draft.setAgentName("demo");
        draft.setQuery("hello");
        return draft;
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

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
