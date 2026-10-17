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

import java.util.List;

/**
 * 消息存储信封：把 Spring AI Message（含 toolCalls / toolResponses）序列化为 JSON 存入 agent_message.content。
 */
public class StoredMessage {

    /** MessageType 的 value（USER/ASSISTANT/SYSTEM/TOOL） */
    private String type;

    /** 消息文本 */
    private String content;

    /** ASSISTANT 消息的工具调用请求 */
    private List<ToolCallDto> toolCalls;

    /** TOOL 消息的工具响应 */
    private List<ToolResponseDto> toolResponses;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<ToolCallDto> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCallDto> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public List<ToolResponseDto> getToolResponses() {
        return toolResponses;
    }

    public void setToolResponses(List<ToolResponseDto> toolResponses) {
        this.toolResponses = toolResponses;
    }

    /** 工具调用请求 DTO（对应 AssistantMessage.ToolCall） */
    public static class ToolCallDto {
        private String id;
        private String type;
        private String name;
        private String arguments;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getArguments() {
            return arguments;
        }

        public void setArguments(String arguments) {
            this.arguments = arguments;
        }
    }

    /** 工具响应 DTO（对应 ToolResponseMessage.ToolResponse） */
    public static class ToolResponseDto {
        private String id;
        private String name;
        private String responseData;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getResponseData() {
            return responseData;
        }

        public void setResponseData(String responseData) {
            this.responseData = responseData;
        }
    }
}
