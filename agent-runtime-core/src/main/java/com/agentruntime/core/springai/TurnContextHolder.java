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
package com.agentruntime.core.springai;

import io.micrometer.context.ContextRegistry;

/**
 * 当前执行轮次。编排器在调用模型前写入，记忆层用它给本轮消息打上同一个 turnId。
 * 流式路径经 context-propagation 透传到 reactor 线程。
 */
public final class TurnContextHolder {

    /** context-propagation 中 turnId 的 key */
    public static final String CONTEXT_KEY = "agentRuntime.turnId";

    private static final InheritableThreadLocal<String> TURN_ID = new InheritableThreadLocal<>();

    private TurnContextHolder() {
    }

    /**
     * 注册到 context-propagation。重复注册无副作用。
     *
     * @param registry micrometer context registry
     */
    public static void registerThreadLocalAccessor(ContextRegistry registry) {
        registry.registerThreadLocalAccessor(CONTEXT_KEY, TURN_ID);
    }

    /**
     * @param turnId 执行轮次，空白表示没有轮次执行
     */
    public static void set(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            TURN_ID.remove();
            return;
        }
        TURN_ID.set(turnId);
    }

    /**
     * @return 当前轮次，未设置时为 null
     */
    public static String turnId() {
        return TURN_ID.get();
    }

    /**
     * 清除当前线程上的轮次。
     */
    public static void clear() {
        TURN_ID.remove();
    }
}
