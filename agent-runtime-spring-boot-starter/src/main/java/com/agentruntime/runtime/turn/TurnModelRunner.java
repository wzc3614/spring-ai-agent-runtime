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
package com.agentruntime.runtime.turn;

import reactor.core.publisher.Flux;

/**
 * 按已保存的 query 再跑一轮模型。执行器会先写好 turnId 上下文。
 */
@FunctionalInterface
public interface TurnModelRunner {

    /**
     * @param userId    用户
     * @param sessionId 会话
     * @param query     本轮用户原文
     * @return 回答 token 流
     */
    Flux<String> open(String userId, String sessionId, String query);
}
