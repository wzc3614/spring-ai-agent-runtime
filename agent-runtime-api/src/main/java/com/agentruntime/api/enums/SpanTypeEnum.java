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
package com.agentruntime.api.enums;

/**
 * Span 类型枚举，对应 agent_runtime_obs.span.span_type 列。
 */
public enum SpanTypeEnum {

    /** 会话级 */
    SESSION("SESSION", "session"),

    /** 单轮对话 */
    TURN("TURN", "turn"),

    /** 一次模型调用 */
    MODEL_CALL("MODEL_CALL", "model call"),

    /** 一次工具调用 */
    TOOL_CALL("TOOL_CALL", "tool call"),

    /** 一次记忆压缩 */
    COMPACTION("COMPACTION", "compaction");

    private final String code;
    private final String desc;

    SpanTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static SpanTypeEnum of(String code) {
        for (SpanTypeEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown SpanTypeEnum code: " + code);
    }
}
