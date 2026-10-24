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
 * Skill 退出原因。超时留给后续轮次级截止时间，本期不发出。
 */
public enum SkillDeactivateReasonEnum {

    /** 本轮调用正常结束 */
    COMPLETED("COMPLETED", "completed"),

    /** 本轮调用失败 */
    ERROR("ERROR", "error"),

    /** 流式调用被取消 */
    CANCELLED("CANCELLED", "cancelled"),

    /** 预留：轮次超时。本期没有轮次级截止时间，不发出 */
    TIMEOUT("TIMEOUT", "timeout");

    private final String code;

    private final String desc;

    SkillDeactivateReasonEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static SkillDeactivateReasonEnum of(String code) {
        for (SkillDeactivateReasonEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown SkillDeactivateReasonEnum code: " + code);
    }
}
