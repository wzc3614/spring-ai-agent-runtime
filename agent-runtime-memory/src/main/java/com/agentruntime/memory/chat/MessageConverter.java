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

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.enums.MessageRoleEnum;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Spring AI Message 与存储信封之间的转换器。
 * 编码：Message -> StoredMessage JSON 字符串（存入 agent_message.content）。
 * 解码：AgentMessage -> Message（解析 content 信封；解析失败按 role 退化为纯文本消息）。
 */
public class MessageConverter {

    private final ObjectMapper objectMapper;

    public MessageConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 把 Spring AI Message 编码为存储 JSON。
     *
     * @param message 原始消息
     * @return JSON 字符串
     */
    public String encode(Message message) {
        StoredMessage stored = new StoredMessage();
        stored.setType(message.getMessageType().getValue());
        stored.setContent(message.getText());

        if (message instanceof AssistantMessage) {
            List<AssistantMessage.ToolCall> toolCalls = ((AssistantMessage) message).getToolCalls();
            if (!CollectionUtils.isEmpty(toolCalls)) {
                List<StoredMessage.ToolCallDto> dtos = new ArrayList<>(toolCalls.size());
                for (AssistantMessage.ToolCall tc : toolCalls) {
                    StoredMessage.ToolCallDto dto = new StoredMessage.ToolCallDto();
                    dto.setId(tc.id());
                    dto.setType(tc.type());
                    dto.setName(tc.name());
                    dto.setArguments(tc.arguments());
                    dtos.add(dto);
                }
                stored.setToolCalls(dtos);
            }
        } else if (message instanceof ToolResponseMessage) {
            List<ToolResponseMessage.ToolResponse> responses = ((ToolResponseMessage) message).getResponses();
            if (!CollectionUtils.isEmpty(responses)) {
                List<StoredMessage.ToolResponseDto> dtos = new ArrayList<>(responses.size());
                for (ToolResponseMessage.ToolResponse tr : responses) {
                    StoredMessage.ToolResponseDto dto = new StoredMessage.ToolResponseDto();
                    dto.setId(tr.id());
                    dto.setName(tr.name());
                    dto.setResponseData(tr.responseData());
                    dtos.add(dto);
                }
                stored.setToolResponses(dtos);
            }
        }
        return writeJson(stored);
    }

    /**
     * 把 AgentMessage 解码为 Spring AI Message。
     *
     * @param agentMessage 持久化消息
     * @return Spring AI Message
     */
    public Message decode(AgentMessage agentMessage) {
        StoredMessage stored = tryParse(agentMessage.getContent());
        if (stored != null && stored.getType() != null) {
            return toMessage(stored);
        }
        // 退化为纯文本消息（如微压缩占位符）
        return toPlainMessage(agentMessage);
    }

    private Message toMessage(StoredMessage stored) {
        String type = stored.getType();
        switch (type) {
            case "system":
                return new SystemMessage(stored.getContent());
            case "user":
                return new UserMessage(stored.getContent());
            case "assistant":
                return new AssistantMessage(stored.getContent(), Map.of(), toToolCalls(stored.getToolCalls()));
            case "tool":
                return new ToolResponseMessage(toToolResponses(stored.getToolResponses()));
            default:
                return new UserMessage(stored.getContent());
        }
    }

    private Message toPlainMessage(AgentMessage agentMessage) {
        String text = agentMessage.getContent();
        if (agentMessage.getRole() == MessageRoleEnum.TOOL) {
            StoredMessage.ToolResponseDto dto = new StoredMessage.ToolResponseDto();
            dto.setId("fallback");
            dto.setResponseData(text);
            return new ToolResponseMessage(Collections.singletonList(
                    new ToolResponseMessage.ToolResponse(dto.getId(), null, dto.getResponseData())));
        }
        if (agentMessage.getRole() == MessageRoleEnum.ASSISTANT) {
            return new AssistantMessage(text);
        }
        if (agentMessage.getRole() == MessageRoleEnum.SYSTEM) {
            return new SystemMessage(text);
        }
        return new UserMessage(text);
    }

    private List<AssistantMessage.ToolCall> toToolCalls(List<StoredMessage.ToolCallDto> dtos) {
        if (CollectionUtils.isEmpty(dtos)) {
            return Collections.emptyList();
        }
        List<AssistantMessage.ToolCall> result = new ArrayList<>(dtos.size());
        for (StoredMessage.ToolCallDto dto : dtos) {
            result.add(new AssistantMessage.ToolCall(dto.getId(), dto.getType(), dto.getName(), dto.getArguments()));
        }
        return result;
    }

    private List<ToolResponseMessage.ToolResponse> toToolResponses(List<StoredMessage.ToolResponseDto> dtos) {
        if (CollectionUtils.isEmpty(dtos)) {
            return Collections.emptyList();
        }
        List<ToolResponseMessage.ToolResponse> result = new ArrayList<>(dtos.size());
        for (StoredMessage.ToolResponseDto dto : dtos) {
            result.add(new ToolResponseMessage.ToolResponse(dto.getId(), dto.getName(), dto.getResponseData()));
        }
        return result;
    }

    private StoredMessage tryParse(String json) {
        if (StringUtils.isEmpty(json) || json.charAt(0) != '{') {
            return null;
        }
        try {
            return objectMapper.readValue(json, StoredMessage.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String writeJson(StoredMessage stored) {
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("encode StoredMessage failed", e);
        }
    }
}
