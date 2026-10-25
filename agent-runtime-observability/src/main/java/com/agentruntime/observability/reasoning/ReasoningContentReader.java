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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

/**
 * 从一次聊天补全 JSON 里取出 {@code reasoning_content}。解析失败当没有思考过程。
 */
public final class ReasoningContentReader {

    private ReasoningContentReader() {
    }

    /**
     * 读取同步响应正文。
     *
     * @param body         响应字节
     * @param objectMapper JSON 解析器
     * @return 思考过程。没有时为空
     */
    public static String fromCompletion(byte[] body, ObjectMapper objectMapper) {
        if (body == null || body.length == 0 || objectMapper == null) {
            return null;
        }
        return fromJson(new String(body, ReasoningConstants.BODY_CHARSET), objectMapper);
    }

    /**
     * 读取一条 SSE data 载荷，或一整个补全 JSON。
     *
     * @param json         JSON 文本
     * @param objectMapper JSON 解析器
     * @return 思考过程。没有时为空
     */
    public static String fromJson(String json, ObjectMapper objectMapper) {
        if (!StringUtils.hasText(json) || objectMapper == null) {
            return null;
        }
        try {
            return textOf(objectMapper.readTree(json));
        } catch (Exception ex) {
            return null;
        }
    }

    private static String textOf(JsonNode root) {
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode choices = root.get(ReasoningConstants.FIELD_CHOICES);
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            return null;
        }
        JsonNode first = choices.get(0);
        String fromMessage = field(first, ReasoningConstants.FIELD_MESSAGE);
        if (fromMessage != null) {
            return fromMessage;
        }
        return field(first, ReasoningConstants.FIELD_DELTA);
    }

    private static String field(JsonNode choice, String container) {
        if (choice == null || !choice.isObject()) {
            return null;
        }
        JsonNode holder = choice.get(container);
        if (holder == null || !holder.isObject()) {
            return null;
        }
        JsonNode reasoning = holder.get(ReasoningConstants.FIELD_REASONING_CONTENT);
        if (reasoning == null || !reasoning.isTextual()) {
            return null;
        }
        String text = reasoning.asText();
        return StringUtils.hasText(text) ? text : null;
    }
}
