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
package com.agentruntime.memory.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.Collections;

/**
 * 带持有者 token 的 Redis 锁。释放和续期都校验 token，避免删掉别人刚拿到的锁。
 */
public class RedisCompareLock {

    private static final Logger log = LoggerFactory.getLogger(RedisCompareLock.class);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisCompareLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * @param key       锁键
     * @param token     持有者
     * @param ttlMillis 过期时间
     * @return 是否拿到锁
     */
    /**
     * @param key 锁键
     * @return 键是否还在。过期或已释放时为 false
     */
    public boolean isHeld(String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    public boolean tryAcquire(String key, String token, long ttlMillis) {
        Boolean acquired = redis.opsForValue().setIfAbsent(key, token, Duration.ofMillis(ttlMillis));
        return Boolean.TRUE.equals(acquired);
    }

    /**
     * 仅当 token 仍是持有者时延长 TTL。
     *
     * @param key       锁键
     * @param token     持有者
     * @param ttlMillis 从现在起的新 TTL
     * @return false 表示锁已经不是自己的，或 Redis 调用失败
     */
    public boolean renew(String key, String token, long ttlMillis) {
        try {
            Long renewed = redis.execute(RENEW_SCRIPT, Collections.singletonList(key), token, String.valueOf(ttlMillis));
            return renewed != null && renewed > 0L;
        } catch (RuntimeException e) {
            log.error("redis lock renew failed, key={}", key, e);
            return false;
        }
    }

    /**
     * 释放锁。失败只记日志，避免盖住调用方 finally 里的原始异常。
     *
     * @param key   锁键
     * @param token 持有者
     */
    public void release(String key, String token) {
        try {
            redis.execute(UNLOCK_SCRIPT, Collections.singletonList(key), token);
        } catch (RuntimeException e) {
            log.error("redis lock release failed, key={}", key, e);
        }
    }
}
