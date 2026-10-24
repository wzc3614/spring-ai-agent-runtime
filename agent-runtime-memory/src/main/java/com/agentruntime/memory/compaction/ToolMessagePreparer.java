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

import com.agentruntime.api.enums.ToolCompactionStrategyEnum;
import com.agentruntime.api.memory.ToolCompactionConstants;
import com.agentruntime.api.memory.ToolCompactionPolicyRegistry;
import com.agentruntime.api.memory.ToolCompactionRule;
import com.agentruntime.memory.chat.MessageConverter;
import com.agentruntime.memory.write.WriteTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写入前整理工具消息：超长原文截断，生成 payload，并给整条消息标上压缩分类。
 * 当轮模型已经从工具回调拿到原文，这里只影响之后的记忆。
 */
public class ToolMessagePreparer {

    private static final Logger log = LoggerFactory.getLogger(ToolMessagePreparer.class);

    private final MessageConverter messageConverter;

    private final ToolCompactionPolicyRegistry registry;

    private final ToolPayloadCodec payloadCodec;

    public ToolMessagePreparer(MessageConverter messageConverter, ToolCompactionPolicyRegistry registry,
                               ToolPayloadCodec payloadCodec) {
        this.messageConverter = messageConverter;
        this.registry = registry;
        this.payloadCodec = payloadCodec;
    }

    /**
     * 填充任务上的 content、payload、compactionClass。
     *
     * @param message 本轮要写入的消息
     * @param task    已分配 seq 的写入任务
     */
    public void apply(Message message, WriteTask task) {
        PreparedWrite prepared = prepare(message);
        task.setContent(messageConverter.encode(prepared.message));
        task.setPayload(prepared.payload);
        task.setCompactionClass(classify(prepared.message));
    }

    private PreparedWrite prepare(Message message) {
        PreparedWrite prepared = new PreparedWrite();
        prepared.message = message;
        if (!(message instanceof ToolResponseMessage)) {
            return prepared;
        }
        ToolResponseMessage toolMessage = (ToolResponseMessage) message;
        if (CollectionUtils.isEmpty(toolMessage.getResponses())) {
            return prepared;
        }
        List<ToolResponseMessage.ToolResponse> limited = new ArrayList<>(toolMessage.getResponses().size());
        Map<String, ToolPayloadEntry> entries = new LinkedHashMap<>();
        boolean truncatedAny = false;
        int index = 0;
        for (ToolResponseMessage.ToolResponse response : toolMessage.getResponses()) {
            String data = response.responseData();
            boolean truncated = false;
            if (data != null && data.length() > ToolCompactionConstants.MAX_PAYLOAD_CHARS) {
                log.warn("tool result truncated, tool={}, callId={}, limit={}",
                        response.name(), response.id(), ToolCompactionConstants.MAX_PAYLOAD_CHARS);
                data = data.substring(0, ToolCompactionConstants.MAX_PAYLOAD_CHARS);
                truncated = true;
                truncatedAny = true;
            }
            limited.add(new ToolResponseMessage.ToolResponse(response.id(), response.name(), data));
            ToolPayloadEntry entry = new ToolPayloadEntry();
            entry.setName(response.name());
            entry.setData(data);
            entry.setTruncated(truncated);
            entries.put(responseKey(response.id(), index), entry);
            index++;
        }
        if (truncatedAny) {
            prepared.message = new ToolResponseMessage(limited);
        }
        prepared.payload = payloadCodec.write(entries);
        return prepared;
    }

    private String classify(Message message) {
        List<String> names = toolNames(message);
        if (names.isEmpty()) {
            return null;
        }
        ToolCompactionStrategyEnum winner = null;
        for (String name : names) {
            ToolCompactionRule rule = registry.getRule(name);
            ToolCompactionStrategyEnum strategy = rule.getStrategy() == null
                    ? ToolCompactionStrategyEnum.QUERY : rule.getStrategy();
            if (winner == null || strategy.getMessageClassPriority() > winner.getMessageClassPriority()) {
                winner = strategy;
            }
        }
        return winner == null ? null : winner.getCode();
    }

    private List<String> toolNames(Message message) {
        List<String> names = new ArrayList<>();
        if (message instanceof AssistantMessage) {
            collectCallNames((AssistantMessage) message, names);
            return names;
        }
        if (message instanceof ToolResponseMessage) {
            collectResponseNames((ToolResponseMessage) message, names);
        }
        return names;
    }

    private void collectCallNames(AssistantMessage message, List<String> names) {
        if (CollectionUtils.isEmpty(message.getToolCalls())) {
            return;
        }
        for (AssistantMessage.ToolCall call : message.getToolCalls()) {
            names.add(call.name());
        }
    }

    private void collectResponseNames(ToolResponseMessage message, List<String> names) {
        if (CollectionUtils.isEmpty(message.getResponses())) {
            return;
        }
        for (ToolResponseMessage.ToolResponse response : message.getResponses()) {
            names.add(response.name());
        }
    }

    private String responseKey(String id, int index) {
        if (StringUtils.hasText(id)) {
            return id;
        }
        return "idx-" + index;
    }

    /** 截断后的消息和对应原文 */
    private static final class PreparedWrite {
        private Message message;
        private String payload;
    }
}
