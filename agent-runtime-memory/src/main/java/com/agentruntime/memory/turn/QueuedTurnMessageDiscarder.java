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

import com.agentruntime.api.repository.MessageRepository;
import com.agentruntime.api.turn.TurnMessageDiscarder;
import com.agentruntime.memory.write.MemoryWriteQueue;

/**
 * 重跑前清掉这一轮的记忆：先抢会话写锁，再删队列和已落库行。
 */
public class QueuedTurnMessageDiscarder implements TurnMessageDiscarder {

    private final MemoryWriteQueue writeQueue;

    private final MessageRepository messageRepository;

    public QueuedTurnMessageDiscarder(MemoryWriteQueue writeQueue, MessageRepository messageRepository) {
        this.writeQueue = writeQueue;
        this.messageRepository = messageRepository;
    }

    @Override
    public void discard(String sessionId, String turnId) {
        writeQueue.discardTurn(sessionId, turnId, () -> messageRepository.deleteBySessionAndTurn(sessionId, turnId));
    }
}
