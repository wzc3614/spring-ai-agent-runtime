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
package com.agentruntime.memory.chat;

import com.agentruntime.api.domain.SummaryContent;
import org.springframework.ai.chat.messages.AbstractMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.Map;

/**
 * 压缩摘要消息：把一段对话历史摘要作为系统消息注入模型上下文。
 *
 * <p>Spring AI {@link MessageType} 枚举不可扩展，故 {@link #getMessageType()} 借 {@link MessageType#SYSTEM}
 * 让模型接受；同时本类承载 {@link com.agentruntime.api.enums.MessageRoleEnum#COMPACTION} 语义，
 * 供 memory 层与日志区分"这是历史摘要"而非普通系统提示。内容以 {@code [conversation summary]} 前缀标识。
 *
 * <p>支持两种渲染：一段式文本（{@link #CompactionMessage(String)}）与结构化分层
 * （{@link #CompactionMessage(SummaryContent)}）。结构化字段全空时回退到一段式 summary。
 *
 * <p>本消息为运行时合成，不写入 agent_message 表（append-only 表只存真实对话轮）。
 */
public class CompactionMessage extends AbstractMessage {

    /** 摘要内容前缀，标识这是一段历史摘要 */
    public static final String SUMMARY_PREFIX = "[conversation summary]\n";

    /**
     * 一段式摘要构造。
     *
     * @param summary 摘要文本
     */
    public CompactionMessage(String summary) {
        super(MessageType.SYSTEM, SUMMARY_PREFIX + summary, Map.of());
    }

    /**
     * 结构化摘要构造：按 Hermes 分层渲染；结构化字段全空时回退到一段式 summary。
     *
     * @param content 结构化摘要内容
     */
    public CompactionMessage(SummaryContent content) {
        super(MessageType.SYSTEM, SUMMARY_PREFIX + render(content), Map.of());
    }

    /**
     * 把结构化摘要渲染为分节文本。结构化字段全空时回退到一段式 summary，保证向后兼容。
     *
     * @param content 结构化摘要内容
     * @return 渲染后的可读文本
     */
    private static String render(SummaryContent content) {
        if (content == null) {
            return "";
        }
        if (content.isStructuredEmpty()) {
            String summary = content.getSummary();
            return summary == null ? "" : summary;
        }
        StringBuilder sb = new StringBuilder();
        appendSection(sb, "Goal", content.getGoal());
        appendListSection(sb, "Constraints & Preferences", merge(content.getConstraints(), content.getPreferences()));
        appendProgress(sb, content);
        appendDecisions(sb, content.getKeyDecisions());
        appendListSection(sb, "Facts", content.getFacts());
        appendListSection(sb, "Open Questions / Next Steps", merge(content.getOpenQuestions(), content.getNextSteps()));
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, String title, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        sb.append("## ").append(title).append('\n').append(value).append('\n');
    }

    private static void appendListSection(StringBuilder sb, String title, List<String> items) {
        if (CollectionUtils.isEmpty(items)) {
            return;
        }
        sb.append("## ").append(title).append('\n');
        for (String item : items) {
            sb.append("- ").append(item).append('\n');
        }
    }

    private static void appendProgress(StringBuilder sb, SummaryContent content) {
        boolean hasDone = !CollectionUtils.isEmpty(content.getProgressDone());
        boolean hasInProgress = !CollectionUtils.isEmpty(content.getProgressInProgress());
        boolean hasBlocked = !CollectionUtils.isEmpty(content.getProgressBlocked());
        if (!hasDone && !hasInProgress && !hasBlocked) {
            return;
        }
        sb.append("## Progress\n");
        appendSubList(sb, "Done", content.getProgressDone());
        appendSubList(sb, "In Progress", content.getProgressInProgress());
        appendSubList(sb, "Blocked", content.getProgressBlocked());
    }

    private static void appendSubList(StringBuilder sb, String sub, List<String> items) {
        if (CollectionUtils.isEmpty(items)) {
            return;
        }
        sb.append("- ").append(sub).append(":\n");
        for (String item : items) {
            sb.append("  - ").append(item).append('\n');
        }
    }

    private static void appendDecisions(StringBuilder sb, List<SummaryContent.Decision> decisions) {
        if (CollectionUtils.isEmpty(decisions)) {
            return;
        }
        sb.append("## Key Decisions\n");
        for (SummaryContent.Decision decision : decisions) {
            sb.append("- ").append(safe(decision.getWhat()));
            if (decision.getWhy() != null && !decision.getWhy().isBlank()) {
                sb.append(" (why: ").append(decision.getWhy()).append(')');
            }
            sb.append('\n');
        }
    }

    private static List<String> merge(List<String> a, List<String> b) {
        if (CollectionUtils.isEmpty(a)) {
            return b;
        }
        if (CollectionUtils.isEmpty(b)) {
            return a;
        }
        List<String> merged = new java.util.ArrayList<>(a.size() + b.size());
        merged.addAll(a);
        merged.addAll(b);
        return merged;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
