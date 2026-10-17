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

import com.agentruntime.api.domain.SummarySnapshot;

import java.util.Optional;

/**
 * 摘要快照仓储，对应 agent_summary_snapshot 表。每次保存生成新版本，支持"摘要之上再摘要"。
 */
public interface SummarySnapshotRepository {

    /**
     * 查询会话当前最新版本的摘要。
     *
     * @param sessionId 会话 ID
     * @return 最新摘要
     */
    Optional<SummarySnapshot> findLatest(String sessionId);

    /**
     * 保存为新版本（version = 旧版本 + 1，无旧版本则为 1）。
     *
     * @param snapshot 摘要实体
     * @return 保存后的实体
     */
    SummarySnapshot save(SummarySnapshot snapshot);
}
