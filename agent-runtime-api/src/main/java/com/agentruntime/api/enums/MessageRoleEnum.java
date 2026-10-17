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
 * 消息角色枚举，对应 agent_message.role 列。
 */
public enum MessageRoleEnum {

    /** 系统消息 */
    SYSTEM("system", "system"),

    /** 用户消息 */
    USER("user", "user"),

    /** 模型消息 */
    ASSISTANT("assistant", "assistant"),

    /** 工具结果消息 */
    TOOL("tool", "tool");

    private final String code;
    private final String desc;

    MessageRoleEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static MessageRoleEnum of(String code) {
        for (MessageRoleEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown MessageRoleEnum code: " + code);
    }
}
