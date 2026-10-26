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
package com.agentruntime.memory.turn;

/**
 * 轮次执行的 Redis 键。单机 Redis，脚本里会按前缀拼已有轮次的键。
 */
public final class TurnRedisKeys {

    /** 轮次哈希前缀 */
    public static final String TURN_PREFIX = "agentruntime:turn:";

    /** 会话当前未完成轮 */
    public static final String ACTIVE_PREFIX = "agentruntime:turn:active:";

    /** 某 agent 仍打开的轮次集合 */
    public static final String OPEN_PREFIX = "agentruntime:turn:open:";

    /** 回放流后缀 */
    public static final String EVENTS_SUFFIX = ":events";

    /** 非幂等工具已开始 */
    public static final String UNSAFE_SUFFIX = ":unsafe";

    private TurnRedisKeys() {
    }

    public static String turnKey(String turnId) {
        return TURN_PREFIX + turnId;
    }

    public static String activeKey(String sessionId) {
        return ACTIVE_PREFIX + sessionId;
    }

    public static String openKey(String agentName) {
        return OPEN_PREFIX + agentName;
    }

    public static String eventsKey(String turnId) {
        return turnKey(turnId) + EVENTS_SUFFIX;
    }

    public static String unsafeKey(String turnId) {
        return turnKey(turnId) + UNSAFE_SUFFIX;
    }
}
