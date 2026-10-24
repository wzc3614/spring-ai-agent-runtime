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
package com.agentruntime.runtime.hook;

/**
 * 轮次钩子打断发生的阶段。
 */
public enum TurnHookPhaseEnum {

    /** 与 Skill 准备并行的第一阶段 */
    PREPARE("PREPARE", "prepare"),

    /** 组装 prompt 之前的第二阶段 */
    BEFORE_PROMPT("BEFORE_PROMPT", "before prompt");

    private final String code;

    private final String desc;

    TurnHookPhaseEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 按编码解析。
     *
     * @param code 编码
     * @return 枚举
     */
    public static TurnHookPhaseEnum of(String code) {
        for (TurnHookPhaseEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown TurnHookPhaseEnum code: " + code);
    }
}
