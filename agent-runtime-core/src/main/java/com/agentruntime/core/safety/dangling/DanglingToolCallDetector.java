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
package com.agentruntime.core.safety.dangling;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 悬空工具调用检测器。加载历史时，若某 assistant 消息请求了 tool_calls 但缺少对应 tool 响应
 * （且不在有意悬空集合中），则剔除该消息，修复大模型消息协议违例（crash 恢复）。
 * HIL 的"等待审批"工具调用属于有意悬空，通过 intentionalPendingIds 跳过。
 */
public class DanglingToolCallDetector {

    /**
     * 修复历史消息，剔除悬空的 assistant tool_call 消息。
     *
     * @param messages               原始历史消息
     * @param intentionalPendingIds  有意悬空的 tool_call id 集合（HIL 等待审批），可为空
     * @return 修复后的消息列表
     */
    public List<Message> repair(List<Message> messages, Set<String> intentionalPendingIds) {
        if (CollectionUtils.isEmpty(messages)) {
            return Collections.emptyList();
        }
        Set<String> pending = intentionalPendingIds == null ? Collections.emptySet() : intentionalPendingIds;
        Set<String> resolved = collectResolvedToolCallIds(messages);

        List<Message> result = new ArrayList<>(messages.size());
        for (Message message : messages) {
            if (isDanglingAssistant(message, resolved, pending)) {
                continue;
            }
            result.add(message);
        }
        return result;
    }

    private Set<String> collectResolvedToolCallIds(List<Message> messages) {
        Set<String> resolved = new HashSet<>();
        for (Message message : messages) {
            if (message instanceof ToolResponseMessage) {
                List<ToolResponseMessage.ToolResponse> responses = ((ToolResponseMessage) message).getResponses();
                if (responses != null) {
                    for (ToolResponseMessage.ToolResponse response : responses) {
                        resolved.add(response.id());
                    }
                }
            }
        }
        return resolved;
    }

    private boolean isDanglingAssistant(Message message, Set<String> resolved, Set<String> pending) {
        if (!(message instanceof AssistantMessage)) {
            return false;
        }
        AssistantMessage assistant = (AssistantMessage) message;
        if (!assistant.hasToolCalls()) {
            return false;
        }
        List<AssistantMessage.ToolCall> toolCalls = assistant.getToolCalls();
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String id = toolCall.id();
            if (id == null || (!resolved.contains(id) && !pending.contains(id))) {
                return true;
            }
        }
        return false;
    }
}
