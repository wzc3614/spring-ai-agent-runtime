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

/**
 * 自定义工具结果渲染的输入。只描述一条工具响应，不包含数据库连接。
 */
public class ToolCompactionInput {

    /** 工具名称 */
    private String toolName;

    /** 工具调用 ID，与助手消息里的 tool_call id 对应 */
    private String callId;

    /** 调用参数 JSON，读路径能关联到助手消息时才有值 */
    private String arguments;

    /** 原始结果。查询类过期并清掉 payload 后可能为空 */
    private String responseData;

    /**
     * 该结果之后已经出现的用户消息数。
     * 读路径组装新一轮 prompt 时会计入尚未落库的当前用户消息，因此上一轮结果至少为 1。
     */
    private int userTurnsSince;

    /** userTurnsSince 为 0 时表示仍在产出该结果的那一轮 */
    private boolean activeTurn;

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getCallId() {
        return callId;
    }

    public void setCallId(String callId) {
        this.callId = callId;
    }

    public String getArguments() {
        return arguments;
    }

    public void setArguments(String arguments) {
        this.arguments = arguments;
    }

    public String getResponseData() {
        return responseData;
    }

    public void setResponseData(String responseData) {
        this.responseData = responseData;
    }

    public int getUserTurnsSince() {
        return userTurnsSince;
    }

    public void setUserTurnsSince(int userTurnsSince) {
        this.userTurnsSince = userTurnsSince;
    }

    public boolean isActiveTurn() {
        return activeTurn;
    }

    public void setActiveTurn(boolean activeTurn) {
        this.activeTurn = activeTurn;
    }
}
