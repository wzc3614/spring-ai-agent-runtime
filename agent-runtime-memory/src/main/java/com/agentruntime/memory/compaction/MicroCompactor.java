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
import com.agentruntime.api.enums.MessageRoleEnum;
import com.agentruntime.api.memory.ToolCompactionPolicyRegistry;
import com.agentruntime.api.spi.TokenEstimator;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 工具结果微压缩器（第二层，纯内存，不调用 LLM）。
 *
 * <p>策略：保留最近 {@code keepRecentMessages} 条原文；对更早的 tool 角色消息，
 * 按工具压缩策略替换为证据指针占位符。M1 暂用默认策略（关键操作永不压缩），
 * 待 tool 消息携带 toolName 后接入按工具粒度策略。
 */
public class MicroCompactor {

    /** 证据指针占位符前缀 */
    private static final String EVIDENCE_PREFIX = "[old tool result cleared, read back via evidence-id=";

    private final ToolCompactionPolicyRegistry policyRegistry;

    private final TokenEstimator tokenEstimator;

    public MicroCompactor(ToolCompactionPolicyRegistry policyRegistry, TokenEstimator tokenEstimator) {
        this.policyRegistry = policyRegistry;
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 对历史消息做微压缩。
     *
     * @param messages           原始历史消息（按 seq 升序）
     * @param keepRecentMessages 保留最近多少条原文
     * @return 压缩上下文
     */
    public CompactionContext compact(List<AgentMessage> messages, int keepRecentMessages) {
        List<AgentMessage> result = new ArrayList<>(messages.size());
        int size = messages.size();
        int compactBoundary = size - keepRecentMessages;
        boolean anyCompacted = false;

        for (int i = 0; i < size; i++) {
            AgentMessage message = messages.get(i);
            if (shouldCompact(message, i, compactBoundary)) {
                result.add(compactToolMessage(message));
                anyCompacted = true;
            } else {
                result.add(message);
            }
        }

        CompactionContext context = new CompactionContext();
        context.setMessages(result);
        context.setMode(anyCompacted ? CompactionModeEnum.MICRO_COMPACTED : CompactionModeEnum.RAW);
        context.setEstimatedTokens(tokenEstimator.estimateMessages(result));
        return context;
    }

    private boolean shouldCompact(AgentMessage message, int index, int boundary) {
        if (message.getRole() != MessageRoleEnum.TOOL) {
            return false;
        }
        if (index >= boundary) {
            return false;
        }
        // M1: 暂用默认策略；关键操作（neverCompact=true）不压缩
        return !policyRegistry.defaultRule().isNeverCompact();
    }

    private AgentMessage compactToolMessage(AgentMessage original) {
        AgentMessage compacted = new AgentMessage();
        compacted.setMessageId(original.getMessageId());
        compacted.setSessionId(original.getSessionId());
        compacted.setSeq(original.getSeq());
        compacted.setRole(original.getRole());
        String evidenceId = "ev-" + UUID.randomUUID().toString().substring(0, 8);
        compacted.setContent(EVIDENCE_PREFIX + evidenceId + "]");
        compacted.setCompacted(true);
        compacted.setEvidenceId(evidenceId);
        compacted.setCreatedAt(original.getCreatedAt());
        return compacted;
    }
}
