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

import com.agentruntime.api.domain.AgentMessage;

/**
 * 消息写入策略 SPI。默认实现 {@code AlwaysWritePolicy} 写入全部消息；
 * 使用者可实现该接口，在风控拦截、拒答、核心参数缺失等场景返回 false 以跳过写入。
 */
public interface MessageWritePolicy {

    /**
     * 判定单条消息是否应写入。
     *
     * @param sessionId 会话 ID
     * @param turnId    本轮 ID
     * @param message   待写入消息
     * @return true 写入，false 跳过
     */
    boolean shouldWrite(String sessionId, String turnId, AgentMessage message);
}
