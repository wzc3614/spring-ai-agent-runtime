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
package com.agentruntime.api.memory;

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.domain.StructuredExtraction;
import com.agentruntime.api.domain.SummaryContent;

import java.util.List;

/**
 * 对话历史摘要器 SPI。当历史占用达到压缩阈值时，由 memory 层异步调用本接口对历史进行摘要。
 *
 * <p>v2：支持结构化提取 + 增量更新，输出结构化 {@link SummaryContent}。
 * 用户可提供自定义 bean 替换默认实现。实现需保证不阻塞主流程（本接口在异步线程内被调用）、
 * 失败抛异常由上层捕获。
 */
public interface ChatHistorySummarizer {

    /**
     * 对历史进行结构化增量摘要。
     *
     * @param sessionId       会话 ID
     * @param existing        既有结构化摘要（null 表示首次摘要；非 null 时应在其基础上增量更新）
     * @param extraction      第一层结构化提取结果（null 表示未启用提取层）
     * @param messages        待压缩的历史消息（按 seq 升序，已排除最近 keepRecentN 条原文）
     * @return 新的结构化摘要内容
     */
    SummaryContent summarize(String sessionId, SummaryContent existing, StructuredExtraction extraction,
                              List<AgentMessage> messages);

    /**
     * 兼容旧一段式调用的默认方法：以既有 summary 文本构造 {@link SummaryContent} 后委托结构化签名。
     * 默认实现仅当未启用结构化时使用。
     *
     * @param sessionId       会话 ID
     * @param existingSummary 既有摘要文本（null 表示首次）
     * @param messages        待压缩历史消息
     * @return 结构化摘要内容（旧实现可仅填充 summary 字段）
     */
    default SummaryContent summarize(String sessionId, String existingSummary, List<AgentMessage> messages) {
        SummaryContent existing = null;
        if (existingSummary != null && !existingSummary.isBlank()) {
            existing = new SummaryContent();
            existing.setSchemaVersion(1);
            existing.setSummary(existingSummary);
        }
        return summarize(sessionId, existing, null, messages);
    }
}
