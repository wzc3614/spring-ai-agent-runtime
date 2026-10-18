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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 基于 Redis 的会话级 FIFO 写入队列。按 sessionId 分键（记忆按 session 共享）。
 * <ul>
 *   <li>enqueue：RPUSH 队列 + SADD 待写 session 集合</li>
 *   <li>peekAll：LRANGE 0 -1，供读侧合并（不消费）</li>
 *   <li>drain：循环 LPOP，仅 worker 在锁内消费</li>
 *   <li>DLQ：失败超限的任务转入死信队列</li>
 * </ul>
 */
public class MemoryWriteQueue {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriteQueue.class);

    private static final String QUEUE_PREFIX = "agentruntime:writeq:";

    private static final String PENDING_SET = "agentruntime:writeq:sessions";

    private static final String DLQ_PREFIX = "agentruntime:writeq:dlq:";

    private final StringRedisTemplate redis;

    private final ObjectMapper objectMapper;

    public MemoryWriteQueue(StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /**
     * 入队一个写入任务。
     *
     * @param task 写入任务
     */
    public void enqueue(WriteTask task) {
        String json = writeJson(task);
        String queueKey = queueKey(task.getSessionId());
        redis.opsForList().rightPush(queueKey, json);
        redis.opsForSet().add(PENDING_SET, task.getSessionId());
    }

    /**
     * 读侧合并用：peek 该 session 队列全部任务（不消费），按入队顺序返回。
     *
     * @param sessionId 会话 ID
     * @return 任务列表（可能为空）
     */
    public List<WriteTask> peekAll(String sessionId) {
        List<String> raw = redis.opsForList().range(queueKey(sessionId), 0, -1);
        if (CollectionUtils.isEmpty(raw)) {
            return Collections.emptyList();
        }
        List<WriteTask> tasks = new ArrayList<>(raw.size());
        for (String json : raw) {
            tasks.add(readJson(json));
        }
        return tasks;
    }

    /**
     * 排空该 session 队列（仅 worker 在锁内调用）。
     *
     * @param sessionId 会话 ID
     * @return 被消费的任务列表（入队顺序）
     */
    public List<WriteTask> drain(String sessionId) {
        List<WriteTask> tasks = new ArrayList<>();
        String queueKey = queueKey(sessionId);
        while (true) {
            String json = redis.opsForList().leftPop(queueKey);
            if (json == null) {
                break;
            }
            tasks.add(readJson(json));
        }
        return tasks;
    }

    /**
     * 写入失败时把任务重新放回队尾等待重试。
     *
     * @param task 任务
     */
    public void requeue(WriteTask task) {
        String json = writeJson(task);
        redis.opsForList().rightPush(queueKey(task.getSessionId()), json);
    }

    /**
     * 转入死信队列。
     *
     * @param task 任务
     */
    public void pushToDlq(WriteTask task) {
        String json = writeJson(task);
        redis.opsForList().rightPush(dlqKey(task.getSessionId()), json);
        log.warn("task moved to dlq, sessionId={}, taskId={}", task.getSessionId(), task.getTaskId());
    }

    /**
     * 查询当前待写 session 集合。
     *
     * @return session ID 集合
     */
    public Set<String> pendingSessions() {
        Set<String> members = redis.opsForSet().members(PENDING_SET);
        return members == null ? Collections.emptySet() : members;
    }

    /**
     * 队列空时从待写集合移除该 session。
     *
     * @param sessionId 会话 ID
     */
    public void removeFromPending(String sessionId) {
        Long size = redis.opsForList().size(queueKey(sessionId));
        if (size == null || size == 0L) {
            redis.opsForSet().remove(PENDING_SET, sessionId);
        }
    }

    private String writeJson(WriteTask task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (Exception e) {
            throw new IllegalStateException("serialize WriteTask failed", e);
        }
    }

    private WriteTask readJson(String json) {
        try {
            return objectMapper.readValue(json, WriteTask.class);
        } catch (Exception e) {
            throw new IllegalStateException("deserialize WriteTask failed", e);
        }
    }

    private String queueKey(String sessionId) {
        return QUEUE_PREFIX + sessionId;
    }

    private String dlqKey(String sessionId) {
        return DLQ_PREFIX + sessionId;
    }
}
