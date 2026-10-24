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
import com.agentruntime.api.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 把已经结束的工具结果视图写回数据库。
 * 查询类过期后清掉 payload 原文；搜索类只替换信封里的视图。
 * 调用方需已持有和写入 worker 相同的 session 写锁。
 */
public class ToolPayloadCleaner {

    private static final Logger log = LoggerFactory.getLogger(ToolPayloadCleaner.class);

    private final MessageRepository messageRepository;

    private final ToolResultRenderer renderer;

    public ToolPayloadCleaner(MessageRepository messageRepository, ToolResultRenderer renderer) {
        this.messageRepository = messageRepository;
        this.renderer = renderer;
    }

    /**
     * 清理一个会话里尚未稳定的工具消息。没有后续用户消息时不改查询和搜索结果。
     *
     * @param sessionId 会话 ID
     */
    public void clean(String sessionId) {
        List<AgentMessage> tools = messageRepository.findUncompactedToolMessages(sessionId);
        if (CollectionUtils.isEmpty(tools)) {
            return;
        }
        List<Long> userSeqs = messageRepository.findUserSeqs(sessionId);
        for (AgentMessage tool : tools) {
            cleanOne(tool, laterUsers(userSeqs, tool.getSeq()));
        }
    }

    private void cleanOne(AgentMessage tool, int userTurnsSince) {
        ToolViewUpdate update = renderer.renderStored(tool, userTurnsSince);
        if (!update.isChanged()) {
            return;
        }
        messageRepository.updateToolView(tool.getMessageId(), update.getContent(), update.getPayload(),
                update.isCompacted());
        log.info("tool view persisted, sessionId={}, messageId={}, compacted={}",
                tool.getSessionId(), tool.getMessageId(), update.isCompacted());
    }

    private int laterUsers(List<Long> userSeqs, long toolSeq) {
        if (CollectionUtils.isEmpty(userSeqs)) {
            return 0;
        }
        int count = 0;
        for (Long seq : userSeqs) {
            if (seq != null && seq > toolSeq) {
                count++;
            }
        }
        return count;
    }
}
