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
 * 工具风险等级枚举，对应 agent_tool_call.risk_level 列。
 */
public enum RiskLevelEnum {

    /** 低风险，可直接执行 */
    LOW("LOW", "low"),

    /** 中风险 */
    MEDIUM("MEDIUM", "medium"),

    /** 高风险 */
    HIGH("HIGH", "high");

    private final String code;
    private final String desc;

    RiskLevelEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static RiskLevelEnum of(String code) {
        for (RiskLevelEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown RiskLevelEnum code: " + code);
    }
}
