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

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.domain.StructuredExtraction;
import com.agentruntime.api.domain.SummaryContent;
import com.agentruntime.api.enums.CompactionFailureKindEnum;
import com.agentruntime.api.memory.ChatHistorySummarizer;
import com.agentruntime.memory.chat.MessageConverter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认增量结构化摘要器：在异步压缩线程内调用用户注入的 {@link ChatModel}，以上一次结构化摘要为基础，
 * 结合第一层提取结果与新增待压缩 Turn，让模型"更新"摘要。输出严格 JSON，由 {@link ObjectMapper} 容错解析为
 * {@link SummaryContent}。失败抛 {@link RuntimeException}，由 {@code SummaryCompactor} 捕获（P3 接入降级）。
 * prompt 文本允许中文（项目规范：仅 prompt 与注释允许中文）。
 */
public class IncrementalRollupSummarizer implements ChatHistorySummarizer {

    private static final Logger log = LoggerFactory.getLogger(IncrementalRollupSummarizer.class);

    /** 系统提示：约束增量更新风格与结构化输出 */
    private static final String SYSTEM_PROMPT = """
            你是对话摘要助手。请在已有结构化摘要的基础上增量更新，结合新对话与结构化提取结果，
            保留仍然有效的事实和约束，修正已变化的进度，补充新的事实与决策，不要臆测、不要加入对话中不存在的信息。
            必须只输出一个合法 JSON 对象，不要输出任何解释文字或 markdown 代码块标记。JSON 结构如下：
            {
              "goal": "用户核心目标的当前表述",
              "constraints": ["硬性约束"],
              "preferences": ["用户偏好与系统设定"],
              "progressDone": ["已完成项"],
              "progressInProgress": ["进行中项"],
              "progressBlocked": ["阻塞项"],
              "keyDecisions": [{"what": "决策内容", "why": "决策理由"}],
              "facts": ["精确事实"],
              "openQuestions": ["待处理项"],
              "nextSteps": ["下一步"]
            }
            没有对应内容的字段输出空数组。
            """;

    private final ChatModel chatModel;
    private final MessageConverter messageConverter;
    private final ObjectMapper objectMapper;

    public IncrementalRollupSummarizer(ChatModel chatModel, MessageConverter messageConverter, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.messageConverter = messageConverter;
        this.objectMapper = objectMapper;
    }

    @Override
    public SummaryContent summarize(String sessionId, SummaryContent existing, StructuredExtraction extraction,
                                     List<AgentMessage> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            return existing != null ? existing : new SummaryContent();
        }
        List<Message> promptMessages = new ArrayList<>(2);
        promptMessages.add(new SystemMessage(SYSTEM_PROMPT));
        promptMessages.add(new UserMessage(buildUserContent(existing, extraction, messages)));
        ChatResponse response = chatModel.call(new Prompt(promptMessages));
        String json = extractText(response);
        SummaryContent content = parseJson(json);
        content.setSchemaVersion(2);
        if (log.isDebugEnabled()) {
            log.debug("summary rolled up, sessionId={}, inputCount={}, hasGoal={}",
                    sessionId, messages.size(), content.getGoal() != null);
        }
        return content;
    }

    private String buildUserContent(SummaryContent existing, StructuredExtraction extraction, List<AgentMessage> messages) {
        StringBuilder sb = new StringBuilder();
        if (existing != null && !existing.isStructuredEmpty()) {
            sb.append("[existing structured summary]\n").append(toExistingJson(existing)).append("\n\n");
        } else if (existing != null && existing.getSummary() != null) {
            sb.append("[existing summary]\n").append(existing.getSummary()).append("\n\n");
        }
        if (extraction != null && hasExtraction(extraction)) {
            sb.append("[structured extraction]\n").append(toExtractionJson(extraction)).append("\n\n");
        }
        sb.append("[new conversation]\n");
        for (AgentMessage message : messages) {
            sb.append(message.getRole().getCode()).append(": ")
                    .append(toReadableText(message)).append('\n');
        }
        return sb.toString();
    }

    private boolean hasExtraction(StructuredExtraction extraction) {
        return !CollectionUtils.isEmpty(extraction.getFacts())
                || !CollectionUtils.isEmpty(extraction.getDecisions())
                || !CollectionUtils.isEmpty(extraction.getConstraints())
                || !CollectionUtils.isEmpty(extraction.getGoals())
                || !CollectionUtils.isEmpty(extraction.getUnresolved());
    }

    private String toExistingJson(SummaryContent existing) {
        try {
            return objectMapper.writeValueAsString(existing);
        } catch (Exception e) {
            return existing.getSummary() == null ? "" : existing.getSummary();
        }
    }

    private String toExtractionJson(StructuredExtraction extraction) {
        try {
            return objectMapper.writeValueAsString(extraction);
        } catch (Exception e) {
            return "";
        }
    }

    private String toReadableText(AgentMessage message) {
        try {
            Message decoded = messageConverter.decode(message);
            String text = decoded.getText();
            return text != null ? text : message.getContent();
        } catch (Exception e) {
            return message.getContent();
        }
    }

    private String extractText(ChatResponse response) {
        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("empty chat response from summarizer model");
        }
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new CompactionException(CompactionFailureKindEnum.INVALID_JSON,
                    "blank summary from summarizer model");
        }
        return stripCodeFence(text);
    }

    private String stripCodeFence(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
            if (trimmed.endsWith("```")) {
                trimmed = trimmed.substring(0, trimmed.length() - 3);
            }
            trimmed = trimmed.trim();
        }
        return trimmed;
    }

    private SummaryContent parseJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            SummaryContent content = new SummaryContent();
            content.setGoal(asText(root.get("goal")));
            content.setConstraints(toStringList(root.get("constraints")));
            content.setPreferences(toStringList(root.get("preferences")));
            content.setProgressDone(toStringList(root.get("progressDone")));
            content.setProgressInProgress(toStringList(root.get("progressInProgress")));
            content.setProgressBlocked(toStringList(root.get("progressBlocked")));
            content.setKeyDecisions(toDecisionList(root.get("keyDecisions")));
            content.setFacts(toStringList(root.get("facts")));
            content.setOpenQuestions(toStringList(root.get("openQuestions")));
            content.setNextSteps(toStringList(root.get("nextSteps")));
            return content;
        } catch (Exception e) {
            throw new CompactionException(CompactionFailureKindEnum.INVALID_JSON,
                    "parse summary json failed", e);
        }
    }

    private String asText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.isTextual() ? node.asText() : node.toString();
        return (text == null || text.isBlank()) ? null : text;
    }

    private List<String> toStringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode element : node) {
            if (element != null && !element.isNull()) {
                String text = element.isTextual() ? element.asText() : element.toString();
                if (text != null && !text.isBlank()) {
                    result.add(text);
                }
            }
        }
        return result;
    }

    private List<SummaryContent.Decision> toDecisionList(JsonNode node) {
        List<SummaryContent.Decision> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode element : node) {
            if (element == null || !element.isObject()) {
                continue;
            }
            SummaryContent.Decision decision = new SummaryContent.Decision();
            JsonNode what = element.get("what");
            JsonNode why = element.get("why");
            if (what != null && !what.isNull()) {
                decision.setWhat(what.asText());
            }
            if (why != null && !why.isNull()) {
                decision.setWhy(why.asText());
            }
            if (decision.getWhat() != null) {
                result.add(decision);
            }
        }
        return result;
    }
}
