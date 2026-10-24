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
package com.agentruntime.memory.history;

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.enums.MessageRoleEnum;
import com.agentruntime.memory.write.WriteTask;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionHistoryTest {

    @Test
    void databaseRowWinsWhenSeqCollides() {
        AgentMessage persisted = message("db-1", 2L, "from-db");
        WriteTask queued = task("queue-1", 2L, "from-queue");

        List<AgentMessage> merged = SessionHistory.merge(List.of(persisted), List.of(queued), 0L);

        assertEquals(1, merged.size());
        assertEquals("db-1", merged.get(0).getMessageId());
        assertEquals("from-db", merged.get(0).getContent());
    }

    @Test
    void queuedSeqIsKeptWhenDatabaseDoesNotHaveIt() {
        AgentMessage persisted = message("db-1", 1L, "persisted");
        WriteTask queued = task("queue-2", 3L, "pending");

        List<AgentMessage> merged = SessionHistory.merge(List.of(persisted), List.of(queued), 0L);

        assertEquals(List.of(1L, 3L), merged.stream().map(AgentMessage::getSeq).toList());
        assertEquals("queue-2", merged.get(1).getMessageId());
    }

    @Test
    void boundaryDropsCoveredMessagesFromBothSides() {
        AgentMessage persisted = message("db-4", 4L, "tail");
        AgentMessage covered = message("db-2", 2L, "covered");
        WriteTask queuedCovered = task("queue-2", 2L, "also-covered");
        WriteTask queuedTail = task("queue-5", 5L, "pending-tail");

        List<AgentMessage> merged = SessionHistory.merge(
                List.of(covered, persisted), List.of(queuedCovered, queuedTail), 3L);

        assertEquals(List.of(4L, 5L), merged.stream().map(AgentMessage::getSeq).toList());
        assertTrue(merged.stream().noneMatch(message -> message.getSeq() <= 3L));
    }

    private static AgentMessage message(String messageId, long seq, String content) {
        AgentMessage message = new AgentMessage();
        message.setMessageId(messageId);
        message.setSessionId("s1");
        message.setSeq(seq);
        message.setRole(MessageRoleEnum.USER);
        message.setContent(content);
        message.setCreatedAt(Instant.EPOCH);
        return message;
    }

    private static WriteTask task(String messageId, long seq, String content) {
        WriteTask task = new WriteTask();
        task.setMessageId(messageId);
        task.setSessionId("s1");
        task.setSeq(seq);
        task.setRoleCode(MessageRoleEnum.USER.getCode());
        task.setContent(content);
        task.setCreatedAtEpochMillis(0L);
        return task;
    }
}
