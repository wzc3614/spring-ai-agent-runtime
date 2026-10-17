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
 * 会话状态枚举，对应 agent_session.status 列。
 */
public enum SessionStatusEnum {

    /** 活跃中 */
    ACTIVE("ACTIVE", "active"),

    /** 正常结束 */
    ENDED("ENDED", "ended"),

    /** 被取消 */
    CANCELLED("CANCELLED", "cancelled"),

    /** 超时 */
    TIMED_OUT("TIMED_OUT", "timed out");

    private final String code;
    private final String desc;

    SessionStatusEnum(String code, String desc) {
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
     * 根据存储 code 反查枚举。
     *
     * @param code 存储值
     * @return 对应枚举
     * @throws IllegalArgumentException 未知的 code
     */
    public static SessionStatusEnum of(String code) {
        for (SessionStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown SessionStatusEnum code: " + code);
    }
}
