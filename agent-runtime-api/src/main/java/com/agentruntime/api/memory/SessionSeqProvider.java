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
package com.agentruntime.api.memory;

/**
 * 会话消息序号分配 SPI。按会话（session）作用域分配（记忆按 session 共享），
 * 默认实现基于 Redis INCR，首次调用时从 DB MAX(seq) 初始化，保证 seq 单调递增、跨实例一致。
 * 分配出的 seq 随 WriteTask 落库，UNIQUE(session_id, seq) 兜底幂等。
 */
public interface SessionSeqProvider {

    /**
     * 原子分配会话内下一个 seq。
     *
     * @param sessionId 会话 ID
     * @return 下一个 seq
     */
    long nextSeq(String sessionId);
}
