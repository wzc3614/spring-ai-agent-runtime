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
import com.agentruntime.api.spi.TransactionPort;
import com.agentruntime.memory.compaction.ToolPayloadCleaner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 写入 worker：按 session 加锁后认领处理中列表，整批落库成功才确认。
 * 失败时任务仍留在处理中列表，重试次数写回原条目，不会在弹出之后丢失。
 */
public class MemoryWriteWorker {

    private static final Logger log = LoggerFactory.getLogger(MemoryWriteWorker.class);

    private final MemoryWriteQueue queue;

    private final MessageRepository messageRepository;

    private final TransactionPort transactionPort;

    private final SessionSeqProvider seqProvider;

    private final ToolPayloadCleaner payloadCleaner;

    /** 锁 TTL（毫秒），需大于单批最大写入耗时 */
    private long lockTtlMillis = WriteQueueConstants.DEFAULT_LOCK_TTL_MILLIS;

    /** 单个任务允许写回处理中列表的最大 retryCount */
    private int maxRetries = 3;

    public MemoryWriteWorker(MemoryWriteQueue queue, MessageRepository messageRepository,
                             TransactionPort transactionPort, SessionSeqProvider seqProvider,
                             ToolPayloadCleaner payloadCleaner) {
        this.queue = queue;
        this.messageRepository = messageRepository;
        this.transactionPort = transactionPort;
        this.seqProvider = seqProvider;
        this.payloadCleaner = payloadCleaner;
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
        try {
            queue.tryWithWriteLock(sessionId, lockTtlMillis, () -> processLocked(sessionId));
        } catch (RuntimeException e) {
            log.error("write session failed, sessionId={}", sessionId, e);
        }
    }

    private void processLocked(String sessionId) {
        List<String> raws = queue.claim(sessionId);
        if (CollectionUtils.isEmpty(raws)) {
            queue.releaseIfIdle(sessionId);
            return;
        }
        List<ParsedWrite> parsed = parse(sessionId, raws);
        if (parsed.isEmpty()) {
            queue.releaseIfIdle(sessionId);
            return;
        }
        if (writeOrRetry(sessionId, parsed)) {
            cleanPayloads(sessionId);
        }
    }

    private List<ParsedWrite> parse(String sessionId, List<String> raws) {
        List<ParsedWrite> parsed = new ArrayList<>();
        for (String raw : raws) {
            WriteTask task = queue.tryDeserialize(raw);
            if (task == null) {
                queue.deadLetterRaw(sessionId, raw);
                continue;
            }
            ParsedWrite item = new ParsedWrite();
            item.setRaw(raw);
            item.setTask(task);
            parsed.add(item);
        }
        return parsed;
    }

    private boolean writeOrRetry(String sessionId, List<ParsedWrite> parsed) {
        try {
            transactionPort.executeInTransaction((Runnable) () -> appendAll(parsed));
            queue.ack(sessionId, rawsOf(parsed));
            return true;
        } catch (Exception e) {
            log.error("batch write failed, will retry, sessionId={}, taskCount={}", sessionId, parsed.size(), e);
            settleFailure(sessionId, parsed);
            return false;
        }
    }

    /**
     * 本批落库之后，在同一把写锁里把已过期的工具视图写回。
     * 清理失败不影响已经确认的消息，下一轮写入会再试。
     */
    private void cleanPayloads(String sessionId) {
        if (payloadCleaner == null) {
            return;
        }
        try {
            payloadCleaner.clean(sessionId);
        } catch (RuntimeException e) {
            log.error("tool payload clean failed, sessionId={}", sessionId, e);
        }
    }

    private void appendAll(List<ParsedWrite> parsed) {
        for (ParsedWrite item : parsed) {
            appendOne(item.getTask());
        }
    }

    /**
     * 同一 seq 已存在且 messageId 相同，视为上次提交成功后的重试。
     * messageId 不同则重新分配 seq，避免 ON CONFLICT 把另一条消息静默丢掉。
     */
    private void appendOne(WriteTask task) {
        AgentMessage message = WriteTaskConverter.toMessage(task);
        if (messageRepository.append(message) > 0) {
            return;
        }
        if (sameMessage(task)) {
            return;
        }
        long reallocated = seqProvider.nextSeq(task.getSessionId());
        task.setSeq(reallocated);
        message.setSeq(reallocated);
        if (messageRepository.append(message) == 0) {
            throw new IllegalStateException("message seq still conflicts after reallocation, sessionId="
                    + task.getSessionId() + ", seq=" + reallocated);
        }
        log.warn("message seq conflict, reallocated, sessionId={}, messageId={}, seq={}",
                task.getSessionId(), task.getMessageId(), reallocated);
    }

    private boolean sameMessage(WriteTask task) {
        Optional<AgentMessage> existing = messageRepository.findBySessionAndSeq(task.getSessionId(), task.getSeq());
        return existing.isPresent() && task.getMessageId().equals(existing.get().getMessageId());
    }

    private void settleFailure(String sessionId, List<ParsedWrite> parsed) {
        List<String> deadLetters = new ArrayList<>();
        List<String> retryable = new ArrayList<>();
        for (ParsedWrite item : parsed) {
            WriteTask task = item.getTask();
            if (task.getRetryCount() < maxRetries) {
                task.setRetryCount(task.getRetryCount() + 1);
                retryable.add(queue.serialize(task));
                continue;
            }
            deadLetters.add(queue.serialize(task));
            log.warn("task moved to dlq, sessionId={}, taskId={}", task.getSessionId(), task.getTaskId());
        }
        queue.settle(sessionId, deadLetters, retryable);
    }

    private static List<String> rawsOf(List<ParsedWrite> parsed) {
        List<String> raws = new ArrayList<>(parsed.size());
        for (ParsedWrite item : parsed) {
            raws.add(item.getRaw());
        }
        return raws;
    }

    public void setLockTtlMillis(long lockTtlMillis) {
        this.lockTtlMillis = lockTtlMillis;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    /** 认领到的原始 JSON 和解析后的任务。确认删除时必须用原始 JSON，不能重新序列化。 */
    private static final class ParsedWrite {

        private String raw;

        private WriteTask task;

        public String getRaw() {
            return raw;
        }

        public void setRaw(String raw) {
            this.raw = raw;
        }

        public WriteTask getTask() {
            return task;
        }

        public void setTask(WriteTask task) {
            this.task = task;
        }
    }
}
