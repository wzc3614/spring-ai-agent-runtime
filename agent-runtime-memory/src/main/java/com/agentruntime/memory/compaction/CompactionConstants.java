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
package com.agentruntime.memory.compaction;

import java.util.List;

/**
 * 压缩管道常量：上下文溢出关键词与观测事件载荷字段。
 */
public final class CompactionConstants {

    /** 模型上下文不足的异常文本关键词（小写匹配） */
    public static final List<String> CONTEXT_OVERFLOW_KEYWORDS = List.of(
            "context_length",
            "context length",
            "maximum context",
            "too many tokens",
            "token limit",
            "context window",
            "prompt is too long",
            "max_tokens"
    );

    /** 观测事件载荷：会话 ID */
    public static final String PAYLOAD_SESSION_ID = "sessionId";

    /** 观测事件载荷：失败阶段 */
    public static final String PAYLOAD_STAGE = "stage";

    /** 观测事件载荷：失败原因 */
    public static final String PAYLOAD_KIND = "kind";

    /** 观测事件载荷：错误信息 */
    public static final String PAYLOAD_MESSAGE = "message";

    /** 阶段：第一层结构化提取 */
    public static final String STAGE_EXTRACT = "EXTRACT";

    /** 阶段：增量摘要 */
    public static final String STAGE_SUMMARIZE = "SUMMARIZE";

    /** 阶段：第四层结构化校验 */
    public static final String STAGE_VALIDATE = "VALIDATE";

    private CompactionConstants() {
    }
}
