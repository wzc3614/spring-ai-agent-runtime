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
package com.agentruntime.api.budget;

/**
 * 单轮 ReAct 预算的默认值。一步等于一次模型调用，同一次响应里的并行工具调用算同一步。
 */
public final class TurnBudgetConstants {

    /** 单轮最多模型调用次数，含最后一次不再调工具的回答 */
    public static final int DEFAULT_MAX_MODEL_CALLS = 6;

    /** 单轮累计 prompt + completion token 上限 */
    public static final int DEFAULT_MAX_TURN_TOKENS = 64000;

    /** 触顶后返回给用户的固定回复。日志不打印这段文案 */
    public static final String STOP_TEXT = "本轮已达到执行上限，已停止继续调用工具。请缩小问题后重试。";

    private TurnBudgetConstants() {
    }
}
