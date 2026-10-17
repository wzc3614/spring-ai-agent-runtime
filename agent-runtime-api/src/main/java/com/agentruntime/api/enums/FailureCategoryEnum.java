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
 * 工具失败分类枚举，对应 agent_tool_call.failure_category 列。
 * 决定 Harness 是否自动重试：TRANSIENT+幂等才重试，AMBIGUOUS 绝不自动重试。
 */
public enum FailureCategoryEnum {

    /** 瞬时基础设施错误（超时/5xx/连接重置），幂等工具可重试 */
    TRANSIENT("TRANSIENT", "transient"),

    /** 终态业务/参数错误，不重试，直接回填模型 */
    TERMINAL("TERMINAL", "terminal"),

    /** 结果不确定（非幂等操作超时类），绝不自动重试，需确认或转审批 */
    AMBIGUOUS("AMBIGUOUS", "ambiguous");

    private final String code;
    private final String desc;

    FailureCategoryEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static FailureCategoryEnum of(String code) {
        for (FailureCategoryEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown FailureCategoryEnum code: " + code);
    }
}
