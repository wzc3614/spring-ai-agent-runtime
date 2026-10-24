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
package com.agentruntime.memory.history;

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.repository.MessageRepository;
import com.agentruntime.memory.write.MemoryWriteQueue;
import com.agentruntime.memory.write.WriteTask;
import com.agentruntime.memory.write.WriteTaskConverter;

import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话历史装载：数据库已提交行与 Redis 未确认任务按 seq 合并。
 * 同一 seq 以数据库为准。有摘要边界时只保留 seq 更大的原文。
 */
public final class SessionHistory {

    private SessionHistory() {
    }

    /**
     * 读取摘要边界之后的历史。boundary 为 0 表示还没有可用摘要，读取全部。
     *
     * @param repository         消息仓储
     * @param queue              写队列
     * @param sessionId          会话 ID
     * @param summarizedUpToSeq  已摘要覆盖的最大 seq，不含
     * @return 按 seq 升序的消息
     */
    public static List<AgentMessage> load(MessageRepository repository, MemoryWriteQueue queue,
                                           String sessionId, long summarizedUpToSeq) {
        long boundary = Math.max(summarizedUpToSeq, 0L);
        List<AgentMessage> persisted = boundary > 0
                ? repository.findBySession(sessionId, boundary)
                : repository.findBySession(sessionId);
        return merge(persisted, queue.peekAll(sessionId), boundary);
    }

    /**
     * 合并已落库消息和队列任务。数据库行优先，队列只补充尚未提交的 seq。
     *
     * @param persisted         已落库消息
     * @param queued            队列中的任务，含处理中
     * @param summarizedUpToSeq 小于等于该值的消息丢弃；0 表示不裁剪
     * @return 按 seq 升序的消息
     */
    public static List<AgentMessage> merge(List<AgentMessage> persisted, List<WriteTask> queued,
                                            long summarizedUpToSeq) {
        Map<Long, AgentMessage> bySeq = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(persisted)) {
            for (AgentMessage message : persisted) {
                if (accept(message.getSeq(), summarizedUpToSeq)) {
                    bySeq.put(message.getSeq(), message);
                }
            }
        }
        if (!CollectionUtils.isEmpty(queued)) {
            for (WriteTask task : queued) {
                if (!accept(task.getSeq(), summarizedUpToSeq)) {
                    continue;
                }
                bySeq.putIfAbsent(task.getSeq(), WriteTaskConverter.toMessage(task));
            }
        }
        List<AgentMessage> merged = new ArrayList<>(bySeq.values());
        merged.sort(Comparator.comparingLong(AgentMessage::getSeq));
        return merged;
    }

    private static boolean accept(long seq, long summarizedUpToSeq) {
        return summarizedUpToSeq <= 0L || seq > summarizedUpToSeq;
    }
}
