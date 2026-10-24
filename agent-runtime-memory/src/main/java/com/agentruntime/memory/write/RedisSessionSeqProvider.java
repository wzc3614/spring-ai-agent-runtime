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
import com.agentruntime.api.memory.SessionSeqProvider;
import com.agentruntime.api.repository.MessageRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;

/**
 * 基于 Redis INCR 的会话 seq 分配器。键不存在时，水位取数据库最大 seq 和队列中未落库 seq 的较大值，
 * 避免 Redis 丢失后新消息和队列里的旧序号相撞。分配与设水位在同一个 Lua 里完成。
 */
public class RedisSessionSeqProvider implements SessionSeqProvider {

    private static final Logger log = LoggerFactory.getLogger(RedisSessionSeqProvider.class);

    private static final String SEQ_KEY_PREFIX = "agentruntime:seq:";

    /**
     * 键不存在，或当前值低于水位时，先把键抬到水位再 INCR。
     * 已有更大的值不会被回退。
     */
    private static final DefaultRedisScript<Long> NEXT_SEQ_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('GET', KEYS[1]) local floor = tonumber(ARGV[1]) "
                    + "if (not current) or (tonumber(current) < floor) then redis.call('SET', KEYS[1], floor) end "
                    + "return redis.call('INCR', KEYS[1])",
            Long.class);

    private final StringRedisTemplate redis;

    private final MessageRepository messageRepository;

    private final MemoryWriteQueue writeQueue;

    public RedisSessionSeqProvider(StringRedisTemplate redis, MessageRepository messageRepository,
                                   MemoryWriteQueue writeQueue) {
        this.redis = redis;
        this.messageRepository = messageRepository;
        this.writeQueue = writeQueue;
    }

    @Override
    public long nextSeq(String sessionId) {
        String key = SEQ_KEY_PREFIX + sessionId;
        long queuedMax = writeQueue.maxQueuedSeq(sessionId);
        long floor = queuedMax;
        if (!Boolean.TRUE.equals(redis.hasKey(key))) {
            floor = initialFloor(dbMax(sessionId), queuedMax);
            log.info("seq key initialized, sessionId={}, floor={}", sessionId, floor);
        }
        Long next = redis.execute(NEXT_SEQ_SCRIPT, Collections.singletonList(key), String.valueOf(floor));
        if (next == null) {
            throw new IllegalStateException("redis seq increment returned null, sessionId=" + sessionId);
        }
        return next;
    }

    /**
     * 重建水位。队列里还没落库的 seq 必须算进去。
     *
     * @param dbMax     数据库中的最大 seq，没有消息时为 0
     * @param queuedMax 队列中的最大 seq，没有任务时为 0
     * @return 下一次 INCR 之前写入 Redis 的值
     */
    static long initialFloor(long dbMax, long queuedMax) {
        return Math.max(dbMax, queuedMax);
    }

    private long dbMax(String sessionId) {
        return messageRepository.findLatest(sessionId)
                .map(AgentMessage::getSeq)
                .orElse(0L);
    }
}
