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
package com.agentruntime.observability.stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.util.CollectionUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Observability Redis Stream 封装。提供入队（XADD）、消费组读取（XREADGROUP）、
 * 确认（XACK）、死信（XADD DLQ）能力。span 与 event 共用单 Stream，靠 type 字段区分。
 *
 * <p>消费组创建（XGROUP CREATE MKSTREAM）由使用方在启动时按需触发，本类只做无状态读写。
 */
public class ObservabilityStreamQueue {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityStreamQueue.class);

    private final StringRedisTemplate redis;

    public ObservabilityStreamQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 入队一个写入任务（XADD）。
     *
     * @param task 写入任务
     * @return Stream 分配的 entry id
     */
    public RecordId enqueue(ObservabilityWriteTask task) {
        return redis.opsForStream().add(ObservabilityStreamConstants.STREAM_KEY, toFields(task));
    }

    /**
     * 消费组读取（XREADGROUP GROUP group consumer COUNT count BLOCK block &gt;）。
     *
     * @param consumerName consumer 名（多实例各自独立）
     * @param count        单次最多读取条数
     * @param block        阻塞等待时长
     * @return 读取到的任务列表（可能为空），每条已回填 entryId
     */
    public List<ObservabilityWriteTask> readGroup(String consumerName, int count, Duration block) {
        Consumer consumer = Consumer.from(ObservabilityStreamConstants.CONSUMER_GROUP, consumerName);
        StreamReadOptions options = StreamReadOptions.empty().count(count).block(block);
        StreamOffset<String> offset = StreamOffset.create(
                ObservabilityStreamConstants.STREAM_KEY, ReadOffset.lastConsumed());
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(consumer, options, offset);
        if (CollectionUtils.isEmpty(records)) {
            return Collections.emptyList();
        }
        List<ObservabilityWriteTask> tasks = new ArrayList<>(records.size());
        for (MapRecord<String, Object, Object> record : records) {
            tasks.add(toTask(record));
        }
        return tasks;
    }

    /**
     * 确认消息已处理（XACK）。
     *
     * @param entryId 已处理消息的 entry id
     * @return 成功 ACK 的条数
     */
    public long ack(String entryId) {
        return redis.opsForStream().acknowledge(
                ObservabilityStreamConstants.STREAM_KEY,
                ObservabilityStreamConstants.CONSUMER_GROUP,
                entryId);
    }

    /**
     * 转入死信 Stream（XADD DLQ），保留原任务字段并附加失败原因与重试次数。
     *
     * @param task   失败任务
     * @param reason 失败原因
     * @return DLQ entry id
     */
    public RecordId pushToDlq(ObservabilityWriteTask task, String reason) {
        Map<String, String> fields = toFields(task);
        fields.put(ObservabilityStreamConstants.FIELD_REASON, reason);
        fields.put(ObservabilityStreamConstants.FIELD_RETRY, String.valueOf(task.getRetryCount()));
        log.warn("task moved to dlq, spanId={}, type={}, retryCount={}, reason={}",
                task.getSpanId(), task.getType(), task.getRetryCount(), reason);
        return redis.opsForStream().add(ObservabilityStreamConstants.DLQ_STREAM_KEY, fields);
    }

    /**
     * 幂等创建消费组（XGROUP CREATE）。Stream 不存在时先建 Stream 再从 0 起建组；
     * 组已存在（BUSYGROUP）则忽略。初始化占位消息会被消费侧按未知类型跳过。
     */
    public void ensureGroup() {
        try {
            redis.opsForStream().createGroup(
                    ObservabilityStreamConstants.STREAM_KEY,
                    ReadOffset.from("0"),
                    ObservabilityStreamConstants.CONSUMER_GROUP);
        } catch (RedisSystemException e) {
            if (isBusyGroup(e)) {
                return;
            }
            if (isStreamMissing(e)) {
                redis.opsForStream().add(ObservabilityStreamConstants.STREAM_KEY,
                        Collections.singletonMap("init", "1"));
                redis.opsForStream().createGroup(
                        ObservabilityStreamConstants.STREAM_KEY,
                        ReadOffset.from("0"),
                        ObservabilityStreamConstants.CONSUMER_GROUP);
                return;
            }
            throw e;
        }
    }

    /**
     * 当前消费组 pending 消息总数（XPENDING 概要）。
     */
    public long pendingCount() {
        PendingMessagesSummary summary = redis.opsForStream().pending(
                ObservabilityStreamConstants.STREAM_KEY, ObservabilityStreamConstants.CONSUMER_GROUP);
        return summary == null ? 0L : summary.getTotalPendingMessages();
    }

    /**
     * 接管空闲超时的 pending 消息（XCLAIM）。仅接管其他 consumer 的消息，
     * 自身 pending 由重投机制处理，避免重复消费。
     *
     * @param consumerName 当前 consumer 名
     * @param minIdleTime   最小空闲时长
     * @param count         最多接管条数
     * @return 接管到的任务列表（已回填 entryId）
     */
    public List<ObservabilityWriteTask> claimIdle(String consumerName, Duration minIdleTime, int count) {
        PendingMessages pending = redis.opsForStream().pending(
                ObservabilityStreamConstants.STREAM_KEY,
                ObservabilityStreamConstants.CONSUMER_GROUP,
                Range.unbounded(),
                (long) count);
        if (pending == null || pending.isEmpty()) {
            return Collections.emptyList();
        }
        List<RecordId> idleIds = new ArrayList<>();
        for (PendingMessage pm : pending) {
            if (pm.getElapsedTimeSinceLastDelivery().compareTo(minIdleTime) >= 0
                    && !Objects.equals(pm.getConsumerName(), consumerName)) {
                idleIds.add(pm.getId());
            }
        }
        if (idleIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(
                ObservabilityStreamConstants.STREAM_KEY,
                ObservabilityStreamConstants.CONSUMER_GROUP,
                consumerName,
                minIdleTime,
                idleIds.toArray(new RecordId[0]));
        if (CollectionUtils.isEmpty(claimed)) {
            return Collections.emptyList();
        }
        List<ObservabilityWriteTask> tasks = new ArrayList<>(claimed.size());
        for (MapRecord<String, Object, Object> record : claimed) {
            tasks.add(toTask(record));
        }
        return tasks;
    }

    private static boolean isBusyGroup(RedisSystemException e) {
        return causeMessageContains(e, "BUSYGROUP");
    }

    private static boolean isStreamMissing(RedisSystemException e) {
        return causeMessageContains(e, "requires the key to exist")
                || causeMessageContains(e, "no such key");
    }

    /**
     * Redis 异常常被包装：顶层 message 往往是 "Error in execution"，真实原因在 cause 链中。
     * 遍历 cause 链匹配关键字，避免漏判 BUSYGROUP / stream missing。
     */
    private static boolean causeMessageContains(Throwable throwable, String keyword) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains(keyword)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private Map<String, String> toFields(ObservabilityWriteTask task) {
        Map<String, String> fields = new HashMap<>();
        fields.put(ObservabilityStreamConstants.FIELD_TYPE, task.getType());
        fields.put(ObservabilityStreamConstants.FIELD_SPAN_ID, task.getSpanId());
        fields.put(ObservabilityStreamConstants.FIELD_SESSION_ID, task.getSessionId());
        fields.put(ObservabilityStreamConstants.FIELD_PAYLOAD, task.getPayload());
        fields.put(ObservabilityStreamConstants.FIELD_CREATED_AT, String.valueOf(task.getCreatedAtEpochMillis()));
        return fields;
    }

    private ObservabilityWriteTask toTask(MapRecord<String, Object, Object> record) {
        Map<Object, Object> value = record.getValue();
        ObservabilityWriteTask task = new ObservabilityWriteTask();
        task.setType(str(value.get(ObservabilityStreamConstants.FIELD_TYPE)));
        task.setSpanId(str(value.get(ObservabilityStreamConstants.FIELD_SPAN_ID)));
        task.setSessionId(str(value.get(ObservabilityStreamConstants.FIELD_SESSION_ID)));
        task.setPayload(str(value.get(ObservabilityStreamConstants.FIELD_PAYLOAD)));
        String createdAt = str(value.get(ObservabilityStreamConstants.FIELD_CREATED_AT));
        if (createdAt != null) {
            task.setCreatedAtEpochMillis(Long.parseLong(createdAt));
        }
        String retry = str(value.get(ObservabilityStreamConstants.FIELD_RETRY));
        if (retry != null) {
            task.setRetryCount(Integer.parseInt(retry));
        }
        RecordId id = record.getId();
        task.setEntryId(id == null ? null : id.getValue());
        return task;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
