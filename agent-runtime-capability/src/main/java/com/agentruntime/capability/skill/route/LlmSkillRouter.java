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
package com.agentruntime.capability.skill.route;

import com.agentruntime.api.capability.SkillDescriptor;
import com.agentruntime.api.capability.SkillRouteResult;
import com.agentruntime.api.enums.SkillRouteSourceEnum;
import com.agentruntime.capability.skill.SkillConstants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 轻量模型兜底路由。直接调用 {@link ChatModel}，不进入 AgentRuntime，避免再次路由。
 * 调用失败或 JSON 无法解析时返回空选择，不中断用户请求。
 */
public class LlmSkillRouter {

    private static final Logger logger = LoggerFactory.getLogger(LlmSkillRouter.class);

    private final ChatModel chatModel;

    private final ObjectMapper objectMapper;

    private final int topN;

    public LlmSkillRouter(ChatModel chatModel, ObjectMapper objectMapper, int topN) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
        this.topN = topN < 1 ? SkillConstants.DEFAULT_TOP_N : topN;
    }

    /**
     * 用模型从候选中选出 top-N。
     *
     * @param userText    用户输入
     * @param descriptors 可见 Skill
     * @return 路由结果。解析失败时 source 为 NONE
     */
    public SkillRouteResult route(String userText, List<SkillDescriptor> descriptors) {
        if (chatModel == null || CollectionUtils.isEmpty(descriptors)) {
            return none(descriptors);
        }
        try {
            String content = callModel(userText, descriptors);
            List<String> skillIds = parseSkillIds(content);
            if (skillIds == null) {
                logger.error("skill llm route returned invalid json");
                return none(descriptors);
            }
            SkillRouteResult result = new SkillRouteResult();
            result.setCandidates(ids(descriptors));
            result.setSelected(pick(descriptors, skillIds));
            result.setSource(SkillRouteSourceEnum.LLM);
            return result;
        } catch (RuntimeException ex) {
            logger.error("skill llm route failed", ex);
            return none(descriptors);
        }
    }

    private String callModel(String userText, List<SkillDescriptor> descriptors) {
        String prompt = String.format(SkillConstants.ROUTER_PROMPT, topN, candidateBlock(descriptors),
                userText == null ? "" : userText);
        ChatResponse response = chatModel.call(new Prompt(new UserMessage(prompt)));
        return Optional.ofNullable(response)
                .map(ChatResponse::getResult)
                .map(result -> result.getOutput())
                .map(output -> output.getText())
                .orElse("");
    }

    private String candidateBlock(List<SkillDescriptor> descriptors) {
        StringBuilder builder = new StringBuilder();
        for (SkillDescriptor descriptor : descriptors) {
            builder.append("- id: ").append(descriptor.getSkillId()).append('\n');
            builder.append("  name: ").append(descriptor.getName()).append('\n');
            builder.append("  description: ").append(descriptor.getDescription()).append('\n');
            if (!CollectionUtils.isEmpty(descriptor.getNotFor())) {
                builder.append("  notFor: ").append(String.join(", ", descriptor.getNotFor())).append('\n');
            }
        }
        return builder.toString();
    }

    private List<String> parseSkillIds(String content) {
        if (StringUtils.isBlank(content)) {
            return null;
        }
        String json = extractJson(content);
        if (json == null) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            JsonNode skillIds = node.get("skillIds");
            if (skillIds == null || !skillIds.isArray()) {
                return null;
            }
            List<String> ids = new ArrayList<>();
            for (JsonNode item : skillIds) {
                if (item != null && item.isTextual()) {
                    ids.add(item.asText());
                }
            }
            return ids;
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String extractJson(String content) {
        String trimmed = content.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return trimmed.substring(start, end + 1);
    }

    private List<SkillDescriptor> pick(List<SkillDescriptor> descriptors, List<String> skillIds) {
        Map<String, SkillDescriptor> byId = new LinkedHashMap<>();
        for (SkillDescriptor descriptor : descriptors) {
            byId.put(descriptor.getSkillId(), descriptor);
        }
        List<SkillDescriptor> selected = new ArrayList<>();
        for (String skillId : skillIds) {
            if (selected.size() >= topN) {
                break;
            }
            SkillDescriptor descriptor = byId.get(skillId);
            if (descriptor != null && !selected.contains(descriptor)) {
                selected.add(descriptor);
            }
        }
        return selected;
    }

    private SkillRouteResult none(List<SkillDescriptor> descriptors) {
        SkillRouteResult result = new SkillRouteResult();
        result.setSelected(List.of());
        result.setCandidates(ids(descriptors));
        result.setSource(SkillRouteSourceEnum.NONE);
        return result;
    }

    private List<String> ids(List<SkillDescriptor> descriptors) {
        List<String> ids = new ArrayList<>();
        if (CollectionUtils.isEmpty(descriptors)) {
            return ids;
        }
        for (SkillDescriptor descriptor : descriptors) {
            ids.add(descriptor.getSkillId());
        }
        return ids;
    }
}
