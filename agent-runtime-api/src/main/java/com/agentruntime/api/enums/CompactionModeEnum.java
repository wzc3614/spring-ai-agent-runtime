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
 * 记忆压缩模式枚举，标识注入模型时对历史应用了哪一层压缩。
 */
public enum CompactionModeEnum {

    /** 未压缩，直接注入近期原文 */
    RAW("RAW", "raw"),

    /** 第二层：工具结果微压缩（纯内存，不调 LLM） */
    MICRO_COMPACTED("MICRO_COMPACTED", "micro compacted"),

    /** 第三层：LLM 结构化摘要（按信息类型分层） */
    SUMMARIZED("SUMMARIZED", "summarized");

    private final String code;
    private final String desc;

    CompactionModeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static CompactionModeEnum of(String code) {
        for (CompactionModeEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown CompactionModeEnum code: " + code);
    }
}
