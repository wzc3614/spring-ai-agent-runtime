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
package com.agentruntime.observability.trace;

/**
 * 单次请求调用树的展示常量。费用不在此计算。
 */
public final class TraceTreeConstants {

    /** 入参、出参、错误等文本在页面上的最大字符数 */
    public static final int MAX_VALUE_CHARS = 200;

    /** 思考过程和最终回复在页面上的最大字符数。工具入出参仍用 {@link #MAX_VALUE_CHARS} */
    public static final int MAX_NARRATIVE_CHARS = 800;

    /** 模型调用上的思考过程标签 */
    public static final String LABEL_THINKING = "thinking";

    /** 轮次上的最终回复标签 */
    public static final String LABEL_RESPONSE = "response";
    /** 超长文本的截断标记 */
    public static final String TRUNCATED_SUFFIX = "...";

    /** 模型调用 span 属性：模型名 */
    public static final String ATTR_MODEL_NAME = "modelName";

    /** 模型调用 span 属性：结束原因 */
    public static final String ATTR_FINISH_REASON = "finishReason";

    /** 模型调用 span 属性：输入 token */
    public static final String ATTR_INPUT_TOKENS = "inputTokens";

    /** 模型调用 span 属性：输出 token */
    public static final String ATTR_OUTPUT_TOKENS = "outputTokens";

    /** 工具调用 span 属性：工具名 */
    public static final String ATTR_TOOL_NAME = "toolName";

    /** 会话不存在 */
    public static final String NOTICE_SESSION_NOT_FOUND = "session not found";

    /** 会话里还没有轮次 */
    public static final String NOTICE_NO_TURN = "no turn";

    /** 指定的轮次不属于该会话 */
    public static final String NOTICE_TURN_NOT_FOUND = "turn not found";

    private TraceTreeConstants() {
    }
}
