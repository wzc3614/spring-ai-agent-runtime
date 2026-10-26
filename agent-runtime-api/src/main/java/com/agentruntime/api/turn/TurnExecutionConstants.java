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
package com.agentruntime.api.turn;

/**
 * 轮次执行的错误码、默认时间和 HTTP 头。
 */
public final class TurnExecutionConstants {

    /** 配置前缀 */
    public static final String PROPERTY_PREFIX = "agent.runtime.turn-execution";

    /** 总开关。默认开 */
    public static final String ENABLED_PROPERTY = PROPERTY_PREFIX + ".enabled";

    /** 查询为空 */
    public static final String ERROR_EMPTY_QUERY = "EMPTY_QUERY";

    /** 会话不属于这个 uid */
    public static final String ERROR_SESSION_UID_MISMATCH = "SESSION_UID_MISMATCH";

    /** 轮次不存在 */
    public static final String ERROR_TURN_NOT_FOUND = "TURN_NOT_FOUND";

    /** 轮次不属于这条会话 */
    public static final String ERROR_TURN_NOT_IN_SESSION = "TURN_NOT_IN_SESSION";

    /** 模型或图执行失败 */
    public static final String ERROR_GRAPH_FAILED = "GRAPH_FAILED";

    /** 最后一个订阅者离开，且打开了断开即取消 */
    public static final String ERROR_CLIENT_DISCONNECTED = "CLIENT_DISCONNECTED";

    /** 已有非幂等工具开始执行，不能整轮重跑 */
    public static final String ERROR_NON_IDEMPOTENT_PARTIAL = "NON_IDEMPOTENT_PARTIAL";

    /** 租约已经不属于当前执行者 */
    public static final String ERROR_LEASE_LOST = "LEASE_LOST";

    /** 响应头：本轮 ID */
    public static final String HEADER_TURN_ID = "X-Turn-Id";

    /** 响应头：这次请求是否复用了未完成轮 */
    public static final String HEADER_TURN_REUSED = "X-Turn-Reused";

    /** 回放路径前缀 */
    public static final String REPLAY_PATH = "/agent-runtime/sessions/{sessionId}/turns/{turnId}/events";

    /** 回放查询参数 */
    public static final String PARAM_AFTER_SEQ = "afterSeq";

    /** 回放查询参数 */
    public static final String PARAM_UID = "uid";

    /** 默认租约 30 秒 */
    public static final long DEFAULT_LEASE_TTL_MILLIS = 30_000L;

    /** 默认心跳 10 秒，必须短于租约 */
    public static final long DEFAULT_RENEW_INTERVAL_MILLIS = 10_000L;

    /** 默认恢复扫描间隔 */
    public static final long DEFAULT_RECOVERY_INTERVAL_MILLIS = 15_000L;

    /** 回答事件批量条数 */
    public static final int DEFAULT_EVENT_BATCH_SIZE = 20;

    /** 回答事件批量间隔 */
    public static final long DEFAULT_EVENT_BATCH_INTERVAL_MILLIS = 200L;

    /** 跟随循环的轮询间隔 */
    public static final long EVENT_POLL_INTERVAL_MILLIS = 200L;

    /** 事件流保留 24 小时 */
    public static final long DEFAULT_EVENT_TTL_SECONDS = 24L * 60L * 60L;

    /** 单轮事件流近似最大长度 */
    public static final int DEFAULT_EVENT_MAX_LEN = 10_000;

    private TurnExecutionConstants() {
    }
}
