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
 * 工具结果压缩的默认阈值。单条原文超过上限时截断后仍视为工具调用成功。
 */
public final class ToolCompactionConstants {

    /** 未注册工具命中的默认规则名 */
    public static final String DEFAULT_RULE_NAME = "__default__";

    /** 单条工具原文的最大字符数，1MB */
    public static final int MAX_PAYLOAD_CHARS = 1_048_576;

    /** 搜索类摘要的默认字符上限 */
    public static final int DEFAULT_SUMMARY_MAX_CHARS = 512;

    /** 查询类默认过期轮次：下一条用户消息起不再携带原文 */
    public static final int DEFAULT_QUERY_EXPIRE_TURNS = 1;

    /** 轮次已结束后才允许压缩。0 表示仍处在产出该结果的那一轮 */
    public static final int ACTIVE_TURN_USER_COUNT = 0;

    private ToolCompactionConstants() {
    }
}
