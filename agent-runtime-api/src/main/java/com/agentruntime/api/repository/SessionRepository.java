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

import com.agentruntime.api.domain.AgentSession;
import com.agentruntime.api.enums.SessionStatusEnum;
import com.agentruntime.api.exception.OptimisticLockException;

import java.util.Optional;

/**
 * 会话仓储，对应 agent_session 表的数据访问端口。
 */
public interface SessionRepository {

    /**
     * 根据会话 ID 查询。
     *
     * @param sessionId 会话 ID
     * @return 会话实体
     */
    Optional<AgentSession> findById(String sessionId);

    /**
     * 保存（新增或带乐观锁更新）。
     *
     * @param session 会话实体
     * @return 保存后的实体（version 已递增）
     * @throws OptimisticLockException 版本冲突时抛出
     */
    AgentSession save(AgentSession session);

    /**
     * 更新会话状态。
     *
     * @param sessionId 会话 ID
     * @param status    目标状态
     * @return 受影响行数
     */
    int updateStatus(String sessionId, SessionStatusEnum status);

    /**
     * 刷新最近活跃时间。
     *
     * @param sessionId 会话 ID
     * @return 受影响行数
     */
    int touch(String sessionId);
}
