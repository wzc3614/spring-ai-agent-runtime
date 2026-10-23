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
 * 工具调用状态枚举，对应 agent_tool_call.status 列。
 */
public enum ToolCallStatusEnum {

    /** 进行中 */
    PENDING("PENDING", "pending"),

    /** 成功 */
    SUCCESS("SUCCESS", "success"),

    /** 失败 */
    FAILED("FAILED", "failed"),

    /** 结果已溢出落盘 */
    SPILLED("SPILLED", "spilled"),

    /** 结果不确定（非幂等操作超时类失败，不可自动重试） */
    AMBIGUOUS("AMBIGUOUS", "ambiguous");

    private final String code;
    private final String desc;

    ToolCallStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static ToolCallStatusEnum of(String code) {
        for (ToolCallStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown ToolCallStatusEnum code: " + code);
    }
}
