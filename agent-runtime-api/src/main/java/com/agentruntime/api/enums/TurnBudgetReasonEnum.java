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
 * 单轮预算触顶原因。写入 {@code TURN_BUDGET_EXCEEDED} 事件的 reason 字段。
 */
public enum TurnBudgetReasonEnum {

    /** 模型调用次数达到上限，且这一步仍要求执行工具 */
    STEP_LIMIT("STEP_LIMIT", "step limit"),

    /** 累计 token 达到上限，且这一步仍要求执行工具 */
    TOKEN_LIMIT("TOKEN_LIMIT", "token limit");

    private final String code;

    private final String desc;

    TurnBudgetReasonEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }
}
