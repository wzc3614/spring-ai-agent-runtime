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

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 基于 Redis INCR 的会话 seq 分配器。按 sessionId 作用域（记忆按 session 共享），
 * 首次分配时从 DB MAX(seq) 初始化，保证 seq 单调递增、跨实例一致。
 * 分配出的 seq 随 WriteTask 落库，UNIQUE(session_id, seq) 兜底幂等。
 */
public class RedisSessionSeqProvider implements SessionSeqProvider {

    private static final String SEQ_KEY_PREFIX = "agentruntime:seq:";

    private final StringRedisTemplate redis;

    private final MessageRepository messageRepository;

    public RedisSessionSeqProvider(StringRedisTemplate redis, MessageRepository messageRepository) {
        this.redis = redis;
        this.messageRepository = messageRepository;
    }

    @Override
    public long nextSeq(String sessionId) {
        String key = SEQ_KEY_PREFIX + sessionId;
        Boolean exists = redis.hasKey(key);
        if (exists == null || !exists) {
            long dbMax = messageRepository.findLatest(sessionId)
                    .map(AgentMessage::getSeq)
                    .orElse(0L);
            redis.opsForValue().setIfAbsent(key, String.valueOf(dbMax));
        }
        Long next = redis.opsForValue().increment(key);
        return next == null ? 1L : next;
    }
}
