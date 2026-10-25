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
package com.agentruntime.observability.reasoning;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * 从 OpenAI 兼容响应里取思考过程时用到的字段名。
 */
public final class ReasoningConstants {

    /** 聊天补全路径片段。其它 JSON 接口不解析 */
    public static final String CHAT_COMPLETIONS_PATH = "chat/completions";

    /** 响应里的候选列表 */
    public static final String FIELD_CHOICES = "choices";

    /** 同步响应的消息对象 */
    public static final String FIELD_MESSAGE = "message";

    /** 流式响应的增量对象 */
    public static final String FIELD_DELTA = "delta";

    /** 千问、DeepSeek 等放思考过程的字段。Spring AI 1.0.0 不映射它 */
    public static final String FIELD_REASONING_CONTENT = "reasoning_content";

    /** SSE 数据行前缀 */
    public static final String SSE_DATA_PREFIX = "data:";

    /** SSE 结束标记 */
    public static final String SSE_DONE = "[DONE]";

    /** 网关正文编码 */
    public static final Charset BODY_CHARSET = StandardCharsets.UTF_8;

    private ReasoningConstants() {
    }
}
