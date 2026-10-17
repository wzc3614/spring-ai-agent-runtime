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

import com.agentruntime.api.domain.SpillRecord;

import java.util.Optional;

/**
 * Spill 元数据仓储，对应 agent_spill_record 表。完整原文存于对象存储。
 */
public interface SpillRecordRepository {

    /**
     * 保存 Spill 元数据。
     *
     * @param record Spill 元数据
     * @return 保存后的实体
     */
    SpillRecord save(SpillRecord record);

    /**
     * 根据工具调用 ID 查询 Spill 元数据。
     *
     * @param toolCallId 工具调用 ID
     * @return Spill 元数据
     */
    Optional<SpillRecord> findByToolCall(String toolCallId);

    /**
     * 会话结束时批量清理其 Spill 元数据。
     *
     * @param sessionId 会话 ID
     * @return 受影响行数
     */
    int deleteBySession(String sessionId);
}
