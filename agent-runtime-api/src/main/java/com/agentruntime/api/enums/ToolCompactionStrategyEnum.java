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
 * 工具结果压缩策略。未注册的工具使用 {@link #QUERY}。
 * messageClassPriority 用于一条消息里混有多个工具时，决定整条消息的 compaction_class。
 */
public enum ToolCompactionStrategyEnum {

    /** 用户自定义渲染器 */
    CUSTOM("CUSTOM", "custom", 1),

    /** 查询类：过期后上下文改为短句，原文从 payload 删除 */
    QUERY("QUERY", "query", 2),

    /** 搜索类：轮次结束后上下文改为确定性摘要，原文留在 payload */
    SUMMARIZE("SUMMARIZE", "summarize", 3),

    /** 操作类：原文完整保留，并在会话摘要之后回捞 */
    RETAIN("RETAIN", "retain", 4);

    private final String code;

    private final String desc;

    /** 同一条消息上多个工具时，数值大的策略作为整条消息的分类 */
    private final int messageClassPriority;

    ToolCompactionStrategyEnum(String code, String desc, int messageClassPriority) {
        this.code = code;
        this.desc = desc;
        this.messageClassPriority = messageClassPriority;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public int getMessageClassPriority() {
        return messageClassPriority;
    }

    /**
     * 按存储 code 解析。未知 code 返回 null，调用方按 QUERY 处理。
     *
     * @param code 存储 code
     * @return 策略，未知时为 null
     */
    public static ToolCompactionStrategyEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (ToolCompactionStrategyEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
