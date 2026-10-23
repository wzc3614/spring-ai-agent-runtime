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
 * 第一层零压缩结构化提取器：调一次 LLM，从待压缩 Turn 中抽取事实/决策/约束/目标/未完成项，
 * 原样保留不做总结。输出严格 JSON，由 {@link ObjectMapper} 容错解析为 {@link StructuredExtraction}。
 *
 * <p>本实现由 memory 层提供，在异步压缩线程内被调用，故同步阻塞调用 LLM 不影响主流程。
 * 非法 JSON 抛 {@link IllegalStateException}，由 {@code SummaryCompactor} 捕获（P3 接入降级）。
 * prompt 文本允许中文（项目规范：仅 prompt 与注释允许中文）。
 */
public class StructuredExtractor {

    private static final Logger log = LoggerFactory.getLogger(StructuredExtractor.class);

    /** 系统提示：约束只做结构化抽取、严格输出 JSON、不臆测 */
    private static final String SYSTEM_PROMPT = """
            你是结构化信息抽取助手。请从下面的对话历史中精确抽取以下五类信息，原样保留，不要总结、不要臆测、
            不要加入对话中不存在的信息。必须只输出一个合法 JSON 对象，不要输出任何解释文字或 markdown 代码块标记。
            JSON 结构如下：
            {
              "facts": ["精确事实，如预算、技术选型、配置项"],
              "decisions": [{"what": "决策内容", "why": "决策理由"}],
              "constraints": ["硬性约束，如用户要求、系统限制"],
              "goals": ["用户目标"],
              "unresolved": ["未完成项、待处理项"]
            }
            没有对应内容的字段输出空数组。
            """;

    private final ChatModel chatModel;
    private final MessageConverter messageConverter;
    private final ObjectMapper objectMapper;

    public StructuredExtractor(ChatModel chatModel, MessageConverter messageConverter, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.messageConverter = messageConverter;
        this.objectMapper = objectMapper;
    }

    /**
     * 从待压缩 Turn 中做结构化提取。
     *
     * @param sessionId 会话 ID
     * @param messages  待压缩 Turn（按 seq 升序）
     * @return 结构化提取结果
     * @throws IllegalStateException LLM 返回非法 JSON 时抛出
     */
    public StructuredExtraction extract(String sessionId, List<AgentMessage> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            return new StructuredExtraction();
        }
        List<Message> promptMessages = new ArrayList<>(2);
        promptMessages.add(new SystemMessage(SYSTEM_PROMPT));
        promptMessages.add(new UserMessage(buildUserContent(messages)));
        ChatResponse response = chatModel.call(new Prompt(promptMessages));
        String json = extractText(response);
        StructuredExtraction extraction = parseJson(json);
        if (log.isDebugEnabled()) {
            log.debug("extraction done, sessionId={}, inputCount={}, facts={}",
                    sessionId, messages.size(),
                    extraction.getFacts() == null ? 0 : extraction.getFacts().size());
        }
        return extraction;
    }

    private String buildUserContent(List<AgentMessage> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("[conversation]\n");
        for (AgentMessage message : messages) {
            sb.append(message.getRole().getCode()).append(": ")
                    .append(toReadableText(message)).append('\n');
        }
        return sb.toString();
    }

    /** 解码为可读文本：优先用 MessageConverter 解析信封取正文，失败退化为原始 content */
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
            throw new IllegalStateException("empty chat response from extraction model");
        }
        String text = response.getResult().getOutput().getText();
        if (text == null || text.isBlank()) {
            throw new CompactionException(CompactionFailureKindEnum.INVALID_JSON,
                    "blank extraction from extraction model");
        }
        return stripCodeFence(text);
    }

    /** 容错剥离可能的 markdown 代码块标记 */
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

    private StructuredExtraction parseJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            StructuredExtraction extraction = new StructuredExtraction();
            extraction.setFacts(toStringList(root.get("facts")));
            extraction.setDecisions(toDecisionList(root.get("decisions")));
            extraction.setConstraints(toStringList(root.get("constraints")));
            extraction.setGoals(toStringList(root.get("goals")));
            extraction.setUnresolved(toStringList(root.get("unresolved")));
            return extraction;
        } catch (Exception e) {
            throw new CompactionException(CompactionFailureKindEnum.INVALID_JSON,
                    "parse extraction json failed", e);
        }
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
