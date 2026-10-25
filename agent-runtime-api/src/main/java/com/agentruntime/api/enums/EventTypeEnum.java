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
 * Event 类型枚举，对应 agent_runtime_obs.event_record.event_type 列。
 */
public enum EventTypeEnum {

    /** 模型思维链 */
    THINKING("THINKING", "thinking"),

    /** 最终回复 */
    FINAL_REPLY("FINAL_REPLY", "final reply"),

    /** 工具入参 */
    TOOL_INPUT("TOOL_INPUT", "tool input"),

    /** 工具出参 */
    TOOL_OUTPUT("TOOL_OUTPUT", "tool output"),

    /** 错误 */
    ERROR("ERROR", "error"),

    /** 记忆压缩失败（不驱逐对应 Turn，仅可观测） */
    COMPACTION_FAILED("COMPACTION_FAILED", "compaction failed"),

    /** Skill 路由结果 */
    SKILL_ROUTED("SKILL_ROUTED", "skill routed"),

    /** Skill 激活，指令已加载 */
    SKILL_ACTIVATED("SKILL_ACTIVATED", "skill activated"),

    /** Skill 执行结束 */
    SKILL_EXECUTED("SKILL_EXECUTED", "skill executed"),

    /** Skill 退出 */
    SKILL_DEACTIVATED("SKILL_DEACTIVATED", "skill deactivated"),

    /** Skill 加载超出 token 预算 */
    SKILL_LOAD_BUDGET_EXCEEDED("SKILL_LOAD_BUDGET_EXCEEDED", "skill load budget exceeded"),

    /** 轮次被钩子提前结束，未调用模型 */
    TURN_INTERRUPTED("TURN_INTERRUPTED", "turn interrupted"),

    /** 单轮步数或累计 token 触顶，停止继续调用工具 */
    TURN_BUDGET_EXCEEDED("TURN_BUDGET_EXCEEDED", "turn budget exceeded");

    private final String code;
    private final String desc;

    EventTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static EventTypeEnum of(String code) {
        for (EventTypeEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown EventTypeEnum code: " + code);
    }
}
