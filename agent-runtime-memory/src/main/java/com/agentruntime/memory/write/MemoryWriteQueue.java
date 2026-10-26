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

import com.agentruntime.memory.redis.RedisCompareLock;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 会话级写入队列。入队列表只负责接收，认领时用 LMOVE 原子搬到处理中列表，
 * 数据库提交成功后再从处理中列表删除。进程死在落库前，消息仍留在处理中列表。
 *
 * <p>入队和待写集合在同一个 Lua 里完成。需要 Redis 6.2+ 的 LMOVE。
 */
public class MemoryWriteQueue {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriteQueue.class);

    /** 入队并登记待写 session */
    private static final DefaultRedisScript<Long> ENQUEUE_SCRIPT = new DefaultRedisScript<>(
            "redis.call('RPUSH', KEYS[1], ARGV[1]); redis.call('SADD', KEYS[2], ARGV[2]); return 1",
            Long.class);

    /**
     * 处理中列表非空时原样返回（崩溃恢复）；否则把入队列表整体搬进处理中列表。
     * KEYS: processing, queue
     */
    private static final DefaultRedisScript<List> CLAIM_SCRIPT = new DefaultRedisScript<>(
            "local pending = redis.call('LLEN', KEYS[1]); "
                    + "if pending > 0 then return redis.call('LRANGE', KEYS[1], 0, -1) end; "
                    + "local n = redis.call('LLEN', KEYS[2]); "
                    + "for i = 1, n do "
                    + "  local item = redis.call('LMOVE', KEYS[2], KEYS[1], 'LEFT', 'RIGHT'); "
                    + "  if not item then break end "
                    + "end; "
                    + "return redis.call('LRANGE', KEYS[1], 0, -1)",
            List.class);

    /**
     * 确认已落库的原始 JSON，并在两个列表都空时移出待写集合。
     * KEYS: processing, queue, pending set
     * ARGV[1] sessionId，其余为要删除的原始 JSON
     */
    private static final DefaultRedisScript<Long> ACK_SCRIPT = new DefaultRedisScript<>(
            "for i = 2, #ARGV do redis.call('LREM', KEYS[1], 1, ARGV[i]) end; "
                    + "if redis.call('LLEN', KEYS[1]) == 0 and redis.call('LLEN', KEYS[2]) == 0 then "
                    + "  redis.call('SREM', KEYS[3], ARGV[1]) "
                    + "end; return 1",
            Long.class);

    /**
     * 把一条无法解析的原始 JSON 从处理中列表移进死信。
     * KEYS: processing, dlq
     */
    private static final DefaultRedisScript<Long> DEAD_LETTER_SCRIPT = new DefaultRedisScript<>(
            "redis.call('LREM', KEYS[1], 1, ARGV[1]); redis.call('RPUSH', KEYS[2], ARGV[1]); return 1",
            Long.class);

    /**
     * 整批失败后重写处理中列表，并把耗尽重试的任务转入死信。两个写操作在同一个脚本里。
     * KEYS: processing, dlq, queue, pending set
     * ARGV[1] sessionId，ARGV[2] 死信条数，随后是死信 JSON，再后面是仍需重试的 JSON
     */
    private static final DefaultRedisScript<Long> SETTLE_SCRIPT = new DefaultRedisScript<>(
            "local sessionId = ARGV[1]; local dlqCount = tonumber(ARGV[2]); "
                    + "redis.call('DEL', KEYS[1]); local idx = 3; "
                    + "for i = 1, dlqCount do redis.call('RPUSH', KEYS[2], ARGV[idx]); idx = idx + 1 end; "
                    + "for i = idx, #ARGV do redis.call('RPUSH', KEYS[1], ARGV[i]) end; "
                    + "if redis.call('LLEN', KEYS[1]) == 0 and redis.call('LLEN', KEYS[3]) == 0 then "
                    + "  redis.call('SREM', KEYS[4], sessionId) "
                    + "end; return 1",
            Long.class);

    /** 两个列表都空时才从待写集合移除，避免和入队脚本交错。 */
    private static final DefaultRedisScript<Long> RELEASE_IDLE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('LLEN', KEYS[1]) == 0 and redis.call('LLEN', KEYS[2]) == 0 then "
                    + "return redis.call('SREM', KEYS[3], ARGV[1]) end; return 0",
            Long.class);

    /** 清空本会话的队列。调用方需已持有写锁。 */
    private static final DefaultRedisScript<Long> DISCARD_SCRIPT = new DefaultRedisScript<>(
            "redis.call('DEL', KEYS[1], KEYS[2]) redis.call('SREM', KEYS[3], ARGV[1]) return 1",
            Long.class);

    private final StringRedisTemplate redis;

    private final ObjectMapper objectMapper;

    private final RedisCompareLock lock;

    public MemoryWriteQueue(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.lock = new RedisCompareLock(redis);
    }

    /**
     * 入队一个写入任务，并把它的 session 放进待写集合。
     *
     * @param task 写入任务
     */
    public void enqueue(WriteTask task) {
        String json = serialize(task);
        redis.execute(ENQUEUE_SCRIPT,
                List.of(WriteQueueConstants.queueKey(task.getSessionId()), WriteQueueConstants.PENDING_SET),
                json, task.getSessionId());
    }

    /**
     * 认领该 session 当前要落库的原始 JSON。处理中列表已有数据时不再从入队列表搬新消息。
     *
     * @param sessionId 会话 ID
     * @return 原始 JSON，顺序与入队一致
     */
    public List<String> claim(String sessionId) {
        Object result = redis.execute(CLAIM_SCRIPT, List.of(
                WriteQueueConstants.processingKey(sessionId),
                WriteQueueConstants.queueKey(sessionId)));
        return toStrings(result);
    }

    /**
     * 落库成功后删除对应的处理中条目。
     *
     * @param sessionId 会话 ID
     * @param rawPayloads 认领时拿到的原始 JSON
     */
    public void ack(String sessionId, List<String> rawPayloads) {
        List<String> args = new ArrayList<>();
        args.add(sessionId);
        if (!CollectionUtils.isEmpty(rawPayloads)) {
            args.addAll(rawPayloads);
        }
        redis.execute(ACK_SCRIPT, List.of(
                WriteQueueConstants.processingKey(sessionId),
                WriteQueueConstants.queueKey(sessionId),
                WriteQueueConstants.PENDING_SET), args.toArray());
    }

    /**
     * 无法解析的原始内容转入死信，并从处理中列表移除。
     *
     * @param sessionId 会话 ID
     * @param raw       原始 JSON
     */
    public void deadLetterRaw(String sessionId, String raw) {
        redis.execute(DEAD_LETTER_SCRIPT, List.of(
                WriteQueueConstants.processingKey(sessionId),
                WriteQueueConstants.dlqKey(sessionId)), raw);
        log.warn("undecodable write task moved to dlq, sessionId={}", sessionId);
    }

    /**
     * 本批落库失败后，用仍需重试的任务覆盖处理中列表，耗尽重试的任务转入死信。
     *
     * @param sessionId 会话 ID
     * @param deadLetters 死信 JSON
     * @param retryable  仍需重试的 JSON
     */
    public void settle(String sessionId, List<String> deadLetters, List<String> retryable) {
        List<String> dlq = deadLetters == null ? List.of() : deadLetters;
        List<String> kept = retryable == null ? List.of() : retryable;
        List<String> args = new ArrayList<>();
        args.add(sessionId);
        args.add(String.valueOf(dlq.size()));
        args.addAll(dlq);
        args.addAll(kept);
        redis.execute(SETTLE_SCRIPT, List.of(
                WriteQueueConstants.processingKey(sessionId),
                WriteQueueConstants.dlqKey(sessionId),
                WriteQueueConstants.queueKey(sessionId),
                WriteQueueConstants.PENDING_SET), args.toArray());
    }

    /**
     * 队列与处理中列表都空时，从待写集合移除该 session。
     *
     * @param sessionId 会话 ID
     */
    public void releaseIfIdle(String sessionId) {
        redis.execute(RELEASE_IDLE_SCRIPT, List.of(
                WriteQueueConstants.processingKey(sessionId),
                WriteQueueConstants.queueKey(sessionId),
                WriteQueueConstants.PENDING_SET), sessionId);
    }

    /**
     * 在写锁内清空 Redis 队列，并执行数据库删除。锁被占用时短暂重试。
     *
     * @param sessionId 会话 ID
     * @param dbDelete  持锁期间执行的数据库清理
     */
    public void discardSession(String sessionId, Runnable dbDelete) {
        for (int attempt = 1; attempt <= WriteQueueConstants.DISCARD_LOCK_ATTEMPTS; attempt++) {
            if (tryWithWriteLock(sessionId, WriteQueueConstants.DEFAULT_LOCK_TTL_MILLIS, () -> {
                deleteQueued(sessionId);
                dbDelete.run();
            })) {
                return;
            }
            sleepBeforeDiscardRetry();
        }
        throw new IllegalStateException("write lock busy, sessionId=" + sessionId);
    }

    /**
     * 持锁删掉这一轮还在队列里的任务，并执行数据库删除。
     *
     * @param sessionId 会话
     * @param turnId    轮次
     * @param dbDelete  持锁期间的数据库清理
     */
    public void discardTurn(String sessionId, String turnId, Runnable dbDelete) {
        for (int attempt = 1; attempt <= WriteQueueConstants.DISCARD_LOCK_ATTEMPTS; attempt++) {
            if (tryWithWriteLock(sessionId, WriteQueueConstants.DEFAULT_LOCK_TTL_MILLIS, () -> {
                removeTurnEntries(sessionId, turnId);
                dbDelete.run();
            })) {
                return;
            }
            sleepBeforeDiscardRetry();
        }
        throw new IllegalStateException("write lock busy, sessionId=" + sessionId);
    }

    /**
     * 持有该 session 的写锁执行动作。没拿到锁时不执行。
     *
     * @param sessionId 会话 ID
     * @param ttlMillis 锁 TTL
     * @param action    临界区
     * @return 是否拿到锁并执行
     */
    public boolean tryWithWriteLock(String sessionId, long ttlMillis, Runnable action) {
        String token = UUID.randomUUID().toString();
        String lockKey = WriteQueueConstants.lockKey(sessionId);
        if (!lock.tryAcquire(lockKey, token, ttlMillis)) {
            return false;
        }
        try {
            action.run();
            return true;
        } finally {
            lock.release(lockKey, token);
        }
    }

    /**
     * 读侧合并用：处理中列表在前，入队列表在后。不消费。坏 JSON 跳过，留给 worker 进死信。
     *
     * @param sessionId 会话 ID
     * @return 任务列表，可能为空
     */
    public List<WriteTask> peekAll(String sessionId) {
        List<String> raw = new ArrayList<>();
        raw.addAll(range(WriteQueueConstants.processingKey(sessionId)));
        raw.addAll(range(WriteQueueConstants.queueKey(sessionId)));
        if (raw.isEmpty()) {
            return Collections.emptyList();
        }
        List<WriteTask> tasks = new ArrayList<>(raw.size());
        for (String json : raw) {
            WriteTask task = readIfPossible(json);
            if (task != null) {
                tasks.add(task);
            }
        }
        return tasks;
    }

    /**
     * 入队列表和处理中列表里的最大 seq。序号键丢失时用来抬高重建水位。
     *
     * @param sessionId 会话 ID
     * @return 没有任务时为 0
     */
    public long maxQueuedSeq(String sessionId) {
        long max = 0L;
        for (WriteTask task : peekAll(sessionId)) {
            if (task.getSeq() > max) {
                max = task.getSeq();
            }
        }
        return max;
    }

    /**
     * 当前待写 session 集合。
     *
     * @return session ID 集合
     */
    public Set<String> pendingSessions() {
        Set<String> members = redis.opsForSet().members(WriteQueueConstants.PENDING_SET);
        return members == null ? Collections.emptySet() : members;
    }

    /**
     * @param task 写入任务
     * @return Redis 中保存的 JSON
     */
    public String serialize(WriteTask task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (Exception e) {
            throw new IllegalStateException("serialize WriteTask failed", e);
        }
    }

    /**
     * @param json 原始 JSON
     * @return 解析失败时返回 null
     */
    /**
     * 解析失败时记日志并返回 null。读路径请走 {@link #peekAll(String)}，那里不打日志。
     *
     * @param json 原始 JSON
     * @return 解析失败时返回 null
     */
    public WriteTask tryDeserialize(String json) {
        try {
            return objectMapper.readValue(json, WriteTask.class);
        } catch (Exception e) {
            log.warn("deserialize WriteTask failed", e);
            return null;
        }
    }

    private WriteTask readIfPossible(String json) {
        try {
            return objectMapper.readValue(json, WriteTask.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void deleteQueued(String sessionId) {
        redis.execute(DISCARD_SCRIPT, List.of(
                WriteQueueConstants.queueKey(sessionId),
                WriteQueueConstants.processingKey(sessionId),
                WriteQueueConstants.PENDING_SET), sessionId);
    }

    private void removeTurnEntries(String sessionId, String turnId) {
        removeMatching(WriteQueueConstants.processingKey(sessionId), turnId);
        removeMatching(WriteQueueConstants.queueKey(sessionId), turnId);
    }

    private void removeMatching(String key, String turnId) {
        List<String> raws = range(key);
        for (String raw : raws) {
            WriteTask task = readIfPossible(raw);
            if (task != null && turnId.equals(task.getTurnId())) {
                redis.opsForList().remove(key, 1, raw);
            }
        }
    }

    private List<String> range(String key) {
        List<String> values = redis.opsForList().range(key, 0, -1);
        return values == null ? Collections.emptyList() : values;
    }

    private void sleepBeforeDiscardRetry() {
        try {
            Thread.sleep(WriteQueueConstants.DISCARD_LOCK_WAIT_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("discard lock wait interrupted", e);
        }
    }

    private static List<String> toStrings(Object result) {
        if (result == null) {
            return List.of();
        }
        if (result instanceof String) {
            return List.of((String) result);
        }
        if (!(result instanceof List)) {
            return List.of();
        }
        List<?> items = (List<?>) result;
        List<String> values = new ArrayList<>(items.size());
        for (Object item : items) {
            if (item != null) {
                values.add(String.valueOf(item));
            }
        }
        return values;
    }
}
