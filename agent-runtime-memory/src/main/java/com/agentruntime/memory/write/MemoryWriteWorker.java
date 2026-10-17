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
package com.agentruntime.memory.write;

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.enums.MessageRoleEnum;
import com.agentruntime.api.repository.MessageRepository;
import com.agentruntime.api.spi.TransactionPort;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 写入 worker：周期性扫描待写 session，按 session 加 Redis 分布式锁后串行消费队列，
 * 批量落库（同一事务），失败重投，超限进 DLQ。保证同 session 顺序不丢不重。
 */
public class MemoryWriteWorker {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriteWorker.class);

    private static final String LOCK_PREFIX = "agentruntime:writelock:";

    /** 释放锁的 Lua：仅当 value 等于 token 才 del */
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final MemoryWriteQueue queue;

    private final MessageRepository messageRepository;

    private final TransactionPort transactionPort;

    private final StringRedisTemplate redis;

    /** 锁 TTL（毫秒），需大于单批最大写入耗时 */
    private long lockTtlMillis = 30000L;

    /** 单任务最大重试次数 */
    private int maxRetries = 3;

    public MemoryWriteWorker(MemoryWriteQueue queue, MessageRepository messageRepository,
                             TransactionPort transactionPort, StringRedisTemplate redis) {
        this.queue = queue;
        this.messageRepository = messageRepository;
        this.transactionPort = transactionPort;
        this.redis = redis;
    }

    /**
     * 周期执行。fixedDelay 默认 1s，可由自动配置覆盖。
     */
    @Scheduled(fixedDelay = 1000L)
    public void run() {
        for (String sessionId : queue.pendingSessions()) {
            processSession(sessionId);
        }
    }

    private void processSession(String sessionId) {
        String lockKey = LOCK_PREFIX + sessionId;
        String token = UUID.randomUUID().toString();
        Boolean acquired = redis.opsForValue().setIfAbsent(lockKey, token, Duration.ofMillis(lockTtlMillis));
        if (Boolean.FALSE.equals(acquired)) {
            return;
        }
        try {
            List<WriteTask> tasks = queue.drain(sessionId);
            if (tasks.isEmpty()) {
                queue.removeFromPending(sessionId);
                return;
            }
            writeOrRetry(sessionId, tasks);
        } finally {
            unlock(lockKey, token);
        }
    }

    private void writeOrRetry(String sessionId, List<WriteTask> tasks) {
        try {
            transactionPort.executeInTransaction((Runnable) () -> {
                for (WriteTask task : tasks) {
                    messageRepository.append(toMessage(task));
                }
            });
            queue.removeFromPending(sessionId);
        } catch (Exception e) {
            log.error("batch write failed, will retry, sessionId={}, taskCount={}", sessionId, tasks.size(), e);
            for (WriteTask task : tasks) {
                if (task.getRetryCount() < maxRetries) {
                    task.setRetryCount(task.getRetryCount() + 1);
                    queue.requeue(task);
                } else {
                    queue.pushToDlq(task);
                }
            }
        }
    }

    private AgentMessage toMessage(WriteTask task) {
        AgentMessage message = new AgentMessage();
        message.setMessageId(task.getMessageId());
        message.setSessionId(task.getSessionId());
        message.setSeq(task.getSeq());
        message.setRole(MessageRoleEnum.of(task.getRoleCode()));
        message.setContent(task.getContent());
        message.setCompacted(task.isCompacted());
        message.setEvidenceId(task.getEvidenceId());
        message.setCreatedAt(Instant.ofEpochMilli(task.getCreatedAtEpochMillis()));
        return message;
    }

    private void unlock(String lockKey, String token) {
        redis.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
    }

    public void setLockTtlMillis(long lockTtlMillis) {
        this.lockTtlMillis = lockTtlMillis;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}
