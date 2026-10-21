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

import com.agentruntime.api.domain.EventRecord;
import com.agentruntime.api.domain.Span;
import com.agentruntime.api.repository.EventRepository;
import com.agentruntime.api.repository.SpanRepository;
import com.agentruntime.api.spi.TransactionPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.StringUtils;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.List;

/**
 * Observability Stream 消费者：周期性 XREADGROUP 批量读取，按类型分发落库（事务），
 * XACK 确认。失败重投（retryCount 递增），超限进 DLQ；并周期 XCLAIM 接管宕机 consumer
 * 的 pending 消息。
 *
 * <p>FK 顺序：EVENT 落库前先 {@link SpanRepository#findById} 校验 span 已存在，缺失则重投
 * （span 通常已在同 Stream 前序，很快被消费）；DB 表 ON CONFLICT DO NOTHING 保证重投幂等。
 */
public class ObservabilityStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityStreamConsumer.class);

    private final ObservabilityStreamQueue queue;

    private final SpanRepository spanRepository;

    private final EventRepository eventRepository;

    private final TransactionPort transactionPort;

    private final StringRedisTemplate redis;

    private final ObjectMapper objectMapper;

    private final String consumerName;

    /** 单次读取条数 */
    private int batchSize = 50;

    /** XREADGROUP 阻塞时长 */
    private Duration block = Duration.ofSeconds(2);

    /** pending 空闲超过该时长视为宕机，XCLAIM 接管 */
    private Duration claimIdle = Duration.ofMinutes(1);

    /** 单任务最大重试次数（含首次） */
    private int maxRetry = 3;

    public ObservabilityStreamConsumer(ObservabilityStreamQueue queue,
                                        SpanRepository spanRepository,
                                        EventRepository eventRepository,
                                        TransactionPort transactionPort,
                                        StringRedisTemplate redis,
                                        ObjectMapper objectMapper) {
        this.queue = queue;
        this.spanRepository = spanRepository;
        this.eventRepository = eventRepository;
        this.transactionPort = transactionPort;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.consumerName = resolveConsumerName();
    }

    @PostConstruct
    public void init() {
        queue.ensureGroup();
    }

    /**
     * 周期消费。fixedDelay 默认 1s。
     */
    @Scheduled(fixedDelay = 1000L)
    public void run() {
        claimIdlePending();
        List<ObservabilityWriteTask> tasks = queue.readGroup(consumerName, batchSize, block);
        if (tasks.isEmpty()) {
            return;
        }
        for (ObservabilityWriteTask task : tasks) {
            process(task);
        }
    }

    private void claimIdlePending() {
        if (queue.pendingCount() <= 0L) {
            return;
        }
        List<ObservabilityWriteTask> claimed = queue.claimIdle(consumerName, claimIdle, batchSize);
        for (ObservabilityWriteTask task : claimed) {
            process(task);
        }
    }

    private void process(ObservabilityWriteTask task) {
        String type = task.getType();
        if (!StringUtils.hasText(type)) {
            // 占位 / 未知类型消息，直接确认丢弃
            ackQuietly(task);
            return;
        }
        if (ObservabilityStreamConstants.TYPE_SPAN.equals(type)) {
            processSpan(task);
        } else if (ObservabilityStreamConstants.TYPE_EVENT.equals(type)) {
            processEvent(task);
        } else {
            log.warn("unknown observability task type={}, spanId={}", type, task.getSpanId());
            ackQuietly(task);
        }
    }

    private void processSpan(ObservabilityWriteTask task) {
        try {
            Span span = readJson(task.getPayload(), Span.class);
            transactionPort.executeInTransaction((Runnable) () -> spanRepository.save(span));
            ackQuietly(task);
        } catch (Exception e) {
            log.error("persist span failed, spanId={}, retryCount={}", task.getSpanId(), task.getRetryCount(), e);
            retryOrDlq(task, "persist span failed: " + e.getClass().getSimpleName());
        }
    }

    private void processEvent(ObservabilityWriteTask task) {
        try {
            // FK 校验：span 必须先入库
            if (spanRepository.findById(task.getSpanId()).isEmpty()) {
                retryOrDlq(task, "parent span not found");
                return;
            }
            EventRecord event = readJson(task.getPayload(), EventRecord.class);
            transactionPort.executeInTransaction((Runnable) () -> eventRepository.save(event));
            ackQuietly(task);
        } catch (Exception e) {
            log.error("persist event failed, spanId={}, retryCount={}", task.getSpanId(), task.getRetryCount(), e);
            retryOrDlq(task, "persist event failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 失败重投：retryCount 未达上限则重新 XADD 到队尾并 ACK 原消息；达上限则进 DLQ 后 ACK。
     */
    private void retryOrDlq(ObservabilityWriteTask task, String reason) {
        int nextRetry = task.getRetryCount() + 1;
        task.setRetryCount(nextRetry);
        if (nextRetry >= maxRetry) {
            queue.pushToDlq(task, reason);
            ackQuietly(task);
        } else {
            queue.enqueue(task);
            ackQuietly(task);
        }
    }

    private void ackQuietly(ObservabilityWriteTask task) {
        if (!StringUtils.hasText(task.getEntryId())) {
            return;
        }
        try {
            queue.ack(task.getEntryId());
        } catch (Exception e) {
            log.warn("ack failed, entryId={}", task.getEntryId(), e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("deserialize observability payload failed", e);
        }
    }

    private static String resolveConsumerName() {
        String name = ManagementFactory.getRuntimeMXBean().getName();
        return name.replace('@', ':');
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public void setBlock(Duration block) {
        this.block = block;
    }

    public void setClaimIdle(Duration claimIdle) {
        this.claimIdle = claimIdle;
    }

    public void setMaxRetry(int maxRetry) {
        this.maxRetry = maxRetry;
    }
}
