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
 * 压缩管道失败原因，用于降级判定与观测事件载荷。
 */
public enum CompactionFailureKindEnum {

    /** LLM 调用超时 */
    TIMEOUT("TIMEOUT", "timeout"),

    /** 模型输出 JSON 不合法 */
    INVALID_JSON("INVALID_JSON", "invalid json"),

    /** 摘要模型上下文不足 */
    CONTEXT_OVERFLOW("CONTEXT_OVERFLOW", "context overflow"),

    /** 第四层结构化校验失败 */
    VALIDATION("VALIDATION", "validation"),

    /** 其他未分类异常 */
    OTHER("OTHER", "other");

    private final String code;
    private final String desc;

    CompactionFailureKindEnum(String code, String desc) {
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
    public static CompactionFailureKindEnum of(String code) {
        for (CompactionFailureKindEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown CompactionFailureKindEnum code: " + code);
    }
}
