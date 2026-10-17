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
import com.agentruntime.api.enums.CompactionModeEnum;

import java.util.List;

/**
 * 压缩上下文/结果：压缩后的消息列表、应用的压缩层级、估算 token、是否仍超预算。
 */
public class CompactionContext {

    /** 压缩后的消息列表（注入模型用） */
    private List<AgentMessage> messages;

    /** 应用的压缩层级 */
    private CompactionModeEnum mode;

    /** 压缩后估算 token */
    private int estimatedTokens;

    /** 压缩后是否仍超预算（M1 临时保留最近 N 条 + warn） */
    private boolean overBudget;

    public List<AgentMessage> getMessages() {
        return messages;
    }

    public void setMessages(List<AgentMessage> messages) {
        this.messages = messages;
    }

    public CompactionModeEnum getMode() {
        return mode;
    }

    public void setMode(CompactionModeEnum mode) {
        this.mode = mode;
    }

    public int getEstimatedTokens() {
        return estimatedTokens;
    }

    public void setEstimatedTokens(int estimatedTokens) {
        this.estimatedTokens = estimatedTokens;
    }

    public boolean isOverBudget() {
        return overBudget;
    }

    public void setOverBudget(boolean overBudget) {
        this.overBudget = overBudget;
    }
}
