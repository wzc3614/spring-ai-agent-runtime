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
import com.agentruntime.api.enums.MessageRoleEnum;
import com.agentruntime.api.enums.ToolCompactionStrategyEnum;
import com.agentruntime.api.memory.ToolCompactionConstants;
import com.agentruntime.api.memory.ToolCompactionInput;
import com.agentruntime.api.memory.ToolCompactionPolicyRegistry;
import com.agentruntime.api.memory.ToolCompactionRule;
import com.agentruntime.api.spi.ToolResultCompactor;
import com.agentruntime.memory.chat.StoredMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 按工具策略生成注入模型的工具结果视图。
 * 产出该结果的那一轮始终保留原文；从下一条用户消息起才套用查询过期或搜索摘要。
 * 读路径只改内存副本。物理删除由 {@link ToolPayloadCleaner} 调用 {@link #renderStored}。
 */
public class ToolResultRenderer {

    private static final Logger log = LoggerFactory.getLogger(ToolResultRenderer.class);

    /** 查询类过期后留在上下文里的短句，保留工具名和调用 id，避免悬空 tool_call */
    private static final String QUERY_EXPIRED_MARKER =
            "[query result expired, tool=%s, id=%s, re-invoke if needed]";

    private final ToolCompactionPolicyRegistry registry;

    private final ObjectMapper objectMapper;

    private final ToolPayloadCodec payloadCodec;

    private final SearchResultSummarizer summarizer;

    private final Map<String, ToolResultCompactor> customByCode;

    public ToolResultRenderer(ToolCompactionPolicyRegistry registry, ObjectMapper objectMapper,
                              List<ToolResultCompactor> compactors) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.payloadCodec = new ToolPayloadCodec(objectMapper);
        this.summarizer = new SearchResultSummarizer(objectMapper);
        this.customByCode = indexCompactors(compactors);
    }

    /**
     * 渲染一批历史。incomingUserTurn 为 true 时，把尚未落库的当前用户消息算进轮次。
     * 调用方必须在用户消息写入记忆之前调用，否则同一条用户消息会被算两次。
     *
     * @param messages         按 seq 升序
     * @param incomingUserTurn 是否正在组装新的用户轮次
     * @return 视图替换后的消息，非工具消息原样返回
     */
    public List<AgentMessage> render(List<AgentMessage> messages, boolean incomingUserTurn) {
        if (CollectionUtils.isEmpty(messages)) {
            return List.of();
        }
        Map<String, String> arguments = indexArguments(messages);
        List<Long> userSeqs = userSeqs(messages);
        List<AgentMessage> rendered = new ArrayList<>(messages.size());
        for (AgentMessage message : messages) {
            if (message.getRole() != MessageRoleEnum.TOOL) {
                rendered.add(message);
                continue;
            }
            int since = laterUserCount(userSeqs, message.getSeq());
            if (incomingUserTurn) {
                since++;
            }
            rendered.add(renderContent(message, since, arguments));
        }
        return rendered;
    }

    /**
     * 计算需要写回数据库的视图。查询类过期时清掉 payload 里的原文。
     *
     * @param message        已落库的工具消息
     * @param userTurnsSince 该消息之后已落库的用户消息数，不含尚未写入的当前轮
     * @return 写回内容；无变化时 changed 为 false
     */
    public ToolViewUpdate renderStored(AgentMessage message, int userTurnsSince) {
        ToolViewUpdate update = unchanged(message);
        if (message == null || message.getRole() != MessageRoleEnum.TOOL) {
            return update;
        }
        StoredMessage stored = parse(message.getContent());
        if (stored == null || CollectionUtils.isEmpty(stored.getToolResponses())) {
            return update;
        }
        Map<String, ToolPayloadEntry> payload = payloadCodec.read(message.getPayload());
        boolean contentChanged = false;
        boolean payloadChanged = false;
        boolean settled = true;
        int index = 0;
        for (StoredMessage.ToolResponseDto response : stored.getToolResponses()) {
            ResponseWork work = applyResponse(response, payload, userTurnsSince, null, index);
            contentChanged = contentChanged || work.contentChanged;
            payloadChanged = payloadChanged || work.payloadChanged;
            settled = settled && work.settled;
            index++;
        }
        if (contentChanged) {
            update.setContent(writeStored(stored, message.getContent()));
            update.setChanged(true);
        }
        if (payloadChanged) {
            update.setPayload(payloadCodec.write(payload));
            update.setChanged(true);
        }
        if (settled && !message.isCompacted()) {
            update.setCompacted(true);
            update.setChanged(true);
        }
        return update;
    }

    private AgentMessage renderContent(AgentMessage message, int userTurnsSince, Map<String, String> arguments) {
        StoredMessage stored = parse(message.getContent());
        if (stored == null || CollectionUtils.isEmpty(stored.getToolResponses())) {
            return message;
        }
        Map<String, ToolPayloadEntry> payload = payloadCodec.read(message.getPayload());
        boolean changed = false;
        int index = 0;
        for (StoredMessage.ToolResponseDto response : stored.getToolResponses()) {
            ResponseWork work = applyResponse(response, payload, userTurnsSince, arguments, index);
            changed = changed || work.contentChanged;
            index++;
        }
        if (!changed) {
            return message;
        }
        return copyWithContent(message, writeStored(stored, message.getContent()));
    }

    /**
     * 改信封里的 responseData，并在查询过期时清掉 payload 原文。
     * 读路径传入的 payload map 随后被丢弃，不会写库。
     */
    private ResponseWork applyResponse(StoredMessage.ToolResponseDto response, Map<String, ToolPayloadEntry> payload,
                                       int userTurnsSince, Map<String, String> arguments, int index) {
        String key = responseKey(response, index);
        ToolPayloadEntry entry = payload.get(key);
        String original = resolveOriginal(response, entry);
        String argumentsJson = arguments == null ? null : arguments.get(response.getId());
        String view = renderView(response.getName(), response.getId(), argumentsJson, original, userTurnsSince);
        ResponseWork work = new ResponseWork();
        if (!java.util.Objects.equals(view, response.getResponseData())) {
            response.setResponseData(view);
            work.contentChanged = true;
        }
        ToolCompactionRule rule = registry.getRule(response.getName());
        if (shouldClearPayload(rule, userTurnsSince)) {
            payloadChanged(payload, key, response.getName(), entry, work);
        }
        work.settled = isResponseSettled(rule, userTurnsSince);
        return work;
    }

    private void payloadChanged(Map<String, ToolPayloadEntry> payload, String key, String toolName,
                                ToolPayloadEntry entry, ResponseWork work) {
        ToolPayloadEntry target = entry;
        if (target == null) {
            target = new ToolPayloadEntry();
            target.setName(toolName);
            payload.put(key, target);
            work.payloadChanged = true;
        }
        if (target.getData() != null) {
            target.setData(null);
            work.payloadChanged = true;
        }
    }

    private String renderView(String toolName, String callId, String arguments, String original, int userTurnsSince) {
        if (userTurnsSince <= ToolCompactionConstants.ACTIVE_TURN_USER_COUNT) {
            return original;
        }
        ToolCompactionRule rule = registry.getRule(toolName);
        ToolCompactionStrategyEnum strategy = rule.getStrategy() == null
                ? ToolCompactionStrategyEnum.QUERY : rule.getStrategy();
        if (strategy == ToolCompactionStrategyEnum.RETAIN) {
            return original;
        }
        if (strategy == ToolCompactionStrategyEnum.QUERY) {
            return userTurnsSince >= expireTurns(rule) ? expiredMarker(toolName, callId) : original;
        }
        if (strategy == ToolCompactionStrategyEnum.SUMMARIZE) {
            return summarizer.summarize(original, summaryMaxChars(rule));
        }
        if (strategy == ToolCompactionStrategyEnum.CUSTOM) {
            return renderCustom(rule, toolName, callId, arguments, original, userTurnsSince);
        }
        return original;
    }

    private String renderCustom(ToolCompactionRule rule, String toolName, String callId, String arguments,
                                String original, int userTurnsSince) {
        if (!StringUtils.hasText(rule.getCompactorCode())) {
            log.warn("custom compactor code missing, tool={}", toolName);
            return original;
        }
        ToolResultCompactor compactor = customByCode.get(rule.getCompactorCode());
        if (compactor == null) {
            log.warn("custom compactor not found, code={}, tool={}", rule.getCompactorCode(), toolName);
            return original;
        }
        ToolCompactionInput input = new ToolCompactionInput();
        input.setToolName(toolName);
        input.setCallId(callId);
        input.setArguments(arguments);
        input.setResponseData(original);
        input.setUserTurnsSince(userTurnsSince);
        input.setActiveTurn(false);
        try {
            String rendered = compactor.renderForContext(input);
            return rendered == null ? original : rendered;
        } catch (RuntimeException e) {
            log.warn("custom compactor failed, code={}, tool={}", rule.getCompactorCode(), toolName, e);
            return original;
        }
    }

    private boolean shouldClearPayload(ToolCompactionRule rule, int userTurnsSince) {
        return rule.getStrategy() == ToolCompactionStrategyEnum.QUERY
                && userTurnsSince >= expireTurns(rule);
    }

    private boolean isResponseSettled(ToolCompactionRule rule, int userTurnsSince) {
        ToolCompactionStrategyEnum strategy = rule.getStrategy() == null
                ? ToolCompactionStrategyEnum.QUERY : rule.getStrategy();
        if (strategy == ToolCompactionStrategyEnum.RETAIN) {
            return true;
        }
        if (strategy == ToolCompactionStrategyEnum.QUERY) {
            return userTurnsSince >= expireTurns(rule);
        }
        return userTurnsSince > ToolCompactionConstants.ACTIVE_TURN_USER_COUNT;
    }

    private int expireTurns(ToolCompactionRule rule) {
        if (rule.getExpireAfterTurns() <= 0) {
            return ToolCompactionConstants.DEFAULT_QUERY_EXPIRE_TURNS;
        }
        return rule.getExpireAfterTurns();
    }

    private int summaryMaxChars(ToolCompactionRule rule) {
        if (rule.getSummaryMaxChars() <= 0) {
            return ToolCompactionConstants.DEFAULT_SUMMARY_MAX_CHARS;
        }
        return rule.getSummaryMaxChars();
    }

    private String resolveOriginal(StoredMessage.ToolResponseDto response, ToolPayloadEntry entry) {
        if (entry != null && entry.getData() != null) {
            return entry.getData();
        }
        if (response.getResponseData() != null) {
            return response.getResponseData();
        }
        return "";
    }

    private String expiredMarker(String toolName, String callId) {
        String name = toolName == null ? "" : toolName;
        String id = callId == null ? "" : callId;
        return String.format(QUERY_EXPIRED_MARKER, name, id);
    }

    private String responseKey(StoredMessage.ToolResponseDto response, int index) {
        if (StringUtils.hasText(response.getId())) {
            return response.getId();
        }
        return "idx-" + index;
    }

    private Map<String, String> indexArguments(List<AgentMessage> messages) {
        Map<String, String> arguments = new HashMap<>();
        for (AgentMessage message : messages) {
            if (message.getRole() != MessageRoleEnum.ASSISTANT) {
                continue;
            }
            StoredMessage stored = parse(message.getContent());
            if (stored == null || CollectionUtils.isEmpty(stored.getToolCalls())) {
                continue;
            }
            for (StoredMessage.ToolCallDto call : stored.getToolCalls()) {
                if (StringUtils.hasText(call.getId())) {
                    arguments.put(call.getId(), call.getArguments());
                }
            }
        }
        return arguments;
    }

    private List<Long> userSeqs(List<AgentMessage> messages) {
        List<Long> seqs = new ArrayList<>();
        for (AgentMessage message : messages) {
            if (message.getRole() == MessageRoleEnum.USER) {
                seqs.add(message.getSeq());
            }
        }
        return seqs;
    }

    private int laterUserCount(List<Long> userSeqs, long toolSeq) {
        int count = 0;
        for (Long seq : userSeqs) {
            if (seq > toolSeq) {
                count++;
            }
        }
        return count;
    }

    private StoredMessage parse(String json) {
        if (!StringUtils.hasText(json) || json.charAt(0) != '{') {
            return null;
        }
        try {
            return objectMapper.readValue(json, StoredMessage.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String writeStored(StoredMessage stored, String fallback) {
        try {
            return objectMapper.writeValueAsString(stored);
        } catch (JsonProcessingException e) {
            log.warn("encode tool view failed");
            return fallback;
        }
    }

    private AgentMessage copyWithContent(AgentMessage source, String content) {
        AgentMessage copy = new AgentMessage();
        copy.setMessageId(source.getMessageId());
        copy.setSessionId(source.getSessionId());
        copy.setSeq(source.getSeq());
        copy.setRole(source.getRole());
        copy.setContent(content);
        copy.setCompacted(source.isCompacted());
        copy.setEvidenceId(source.getEvidenceId());
        copy.setPayload(source.getPayload());
        copy.setCompactionClass(source.getCompactionClass());
        copy.setCreatedAt(source.getCreatedAt());
        return copy;
    }

    private ToolViewUpdate unchanged(AgentMessage message) {
        ToolViewUpdate update = new ToolViewUpdate();
        update.setChanged(false);
        if (message != null) {
            update.setContent(message.getContent());
            update.setPayload(message.getPayload());
            update.setCompacted(message.isCompacted());
        }
        return update;
    }

    private Map<String, ToolResultCompactor> indexCompactors(List<ToolResultCompactor> compactors) {
        Map<String, ToolResultCompactor> indexed = new HashMap<>();
        if (CollectionUtils.isEmpty(compactors)) {
            return indexed;
        }
        for (ToolResultCompactor compactor : compactors) {
            if (compactor == null || !StringUtils.hasText(compactor.code())) {
                continue;
            }
            indexed.put(compactor.code(), compactor);
        }
        return indexed;
    }

    /** 单条工具响应的渲染副作用 */
    private static final class ResponseWork {
        private boolean contentChanged;
        private boolean payloadChanged;
        private boolean settled;
    }
}
