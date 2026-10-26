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
package com.agentruntime.api.repository;

import com.agentruntime.api.domain.AgentMessage;

import java.util.List;
import java.util.Optional;

/**
 * 消息仓储，对应 agent_message 表。写入只追加；读取按 seq 有序返回。
 */
public interface MessageRepository {

    /**
     * 追加一条消息。seq 由调用方通过 {@link SessionSeqProvider} 预先分配并 set 到 message 上；
     * 落库用 ON CONFLICT(session_id, seq) DO NOTHING 保证幂等。
     *
     * @param message 消息实体
     * @return 1 表示插入成功，0 表示该 seq 已存在
     */
    int append(AgentMessage message);

    /**
     * 按会话和 seq 精确查询一条消息，用于区分幂等重试和序号冲突。
     *
     * @param sessionId 会话 ID
     * @param seq       消息 seq
     * @return 已落库的消息
     */
    Optional<AgentMessage> findBySessionAndSeq(String sessionId, long seq);

    /**
     * 按会话查询全部消息，按 seq 升序。
     *
     * @param sessionId 会话 ID
     * @return 消息列表
     */
    List<AgentMessage> findBySession(String sessionId);

    /**
     * 按会话查询 seq 大于 fromSeq 的消息，按 seq 升序。
     *
     * @param sessionId 会话 ID
     * @param fromSeq    起始 seq（不含）
     * @return 消息列表
     */
    List<AgentMessage> findBySession(String sessionId, long fromSeq);

    /**
     * 查询会话内最新一条消息（供 seq 分配器从 DB MAX(seq) 初始化）。
     *
     * @param sessionId 会话 ID
     * @return 最新消息
     */
    Optional<AgentMessage> findLatest(String sessionId);

    /**
     * 将消息标记为已微压缩，并写入证据指针。
     *
     * @param messageId  消息 ID
     * @param evidenceId 证据指针
     * @return 受影响行数
     */
    int markCompacted(String messageId, String evidenceId);

    /**
     * 回写工具消息的上下文视图和原文。查询类过期时 payload 中对应 data 已置空。
     *
     * @param messageId 消息 ID
     * @param content   存储信封
     * @param payload   工具原文 JSON，可为 null
     * @param compacted 物理视图是否已经稳定，稳定后清理任务不再扫描
     * @return 受影响行数
     */
    int updateToolView(String messageId, String content, String payload, boolean compacted);

    /**
     * 查询已被会话摘要覆盖、但仍需完整回捞的消息（compaction_class = RETAIN）。
     *
     * @param sessionId 会话 ID
     * @param upToSeq   摘要边界（含）
     * @return 按 seq 升序的消息
     */
    List<AgentMessage> findRetainedUpTo(String sessionId, long upToSeq);

    /**
     * 查询尚未完成物理清理的工具消息。
     *
     * @param sessionId 会话 ID
     * @return 按 seq 升序的 tool 消息
     */
    List<AgentMessage> findUncompactedToolMessages(String sessionId);

    /**
     * 查询会话内用户消息的 seq，供工具结果计算过期轮次。
     *
     * @param sessionId 会话 ID
     * @return 按 seq 升序
     */
    List<Long> findUserSeqs(String sessionId);

    /**
     * 会话结束时批量清理其消息（记忆按 session 共享，clear 走此路径）。
     *
     * @param sessionId 会话 ID
     * @return 受影响行数
     */
    int deleteBySession(String sessionId);

    /**
     * 删除某一轮已经落库的消息。整轮重跑前调用，避免同一句 user 留两份。
     *
     * @param sessionId 会话 ID
     * @param turnId    轮次 ID
     * @return 受影响行数
     */
    int deleteBySessionAndTurn(String sessionId, String turnId);
}
