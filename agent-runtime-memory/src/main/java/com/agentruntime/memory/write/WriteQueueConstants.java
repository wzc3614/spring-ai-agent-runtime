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

/**
 * 记忆异步写队列的 Redis 键和锁参数。
 * 入队列表、处理中列表、待写集合必须一起维护，避免只改其中一处。
 */
public final class WriteQueueConstants {

    /** 待落库 FIFO，RPUSH 入队，从左侧认领 */
    public static final String QUEUE_PREFIX = "agentruntime:writeq:";

    /** 已认领但未确认落库的消息。进程崩溃后从这里恢复 */
    public static final String PROCESSING_PREFIX = "agentruntime:writeq:processing:";

    /** 有未处理消息的 session 集合 */
    public static final String PENDING_SET = "agentruntime:writeq:sessions";

    /** 无法反序列化或重试耗尽的原始任务 */
    public static final String DLQ_PREFIX = "agentruntime:writeq:dlq:";

    /** 同一 session 写路径互斥锁 */
    public static final String LOCK_PREFIX = "agentruntime:writelock:";

    /** 写锁默认 TTL（毫秒），需盖住单批落库 */
    public static final long DEFAULT_LOCK_TTL_MILLIS = 30000L;

    /** clear 抢锁次数。写批次通常短于锁 TTL，这里只覆盖短暂争用 */
    public static final int DISCARD_LOCK_ATTEMPTS = 20;

    /** clear 抢锁间隔（毫秒） */
    public static final long DISCARD_LOCK_WAIT_MILLIS = 100L;

    private WriteQueueConstants() {
    }

    public static String queueKey(String sessionId) {
        return QUEUE_PREFIX + sessionId;
    }

    public static String processingKey(String sessionId) {
        return PROCESSING_PREFIX + sessionId;
    }

    public static String dlqKey(String sessionId) {
        return DLQ_PREFIX + sessionId;
    }

    public static String lockKey(String sessionId) {
        return LOCK_PREFIX + sessionId;
    }
}
