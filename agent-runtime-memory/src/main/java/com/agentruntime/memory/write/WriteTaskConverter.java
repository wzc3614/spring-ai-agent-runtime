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
package com.agentruntime.memory.write;

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.enums.MessageRoleEnum;

import java.time.Instant;

/**
 * {@link WriteTask} 与 {@link AgentMessage} 的转换。读路径合并和落库共用，避免两套字段映射。
 */
public final class WriteTaskConverter {

    private WriteTaskConverter() {
    }

    /**
     * @param task 队列中的写入任务
     * @return 可落库或注入上下文的消息
     */
    public static AgentMessage toMessage(WriteTask task) {
        AgentMessage message = new AgentMessage();
        message.setMessageId(task.getMessageId());
        message.setSessionId(task.getSessionId());
        message.setSeq(task.getSeq());
        message.setRole(MessageRoleEnum.of(task.getRoleCode()));
        message.setContent(task.getContent());
        message.setCompacted(task.isCompacted());
        message.setEvidenceId(task.getEvidenceId());
        message.setPayload(task.getPayload());
        message.setCompactionClass(task.getCompactionClass());
        message.setTurnId(task.getTurnId());
        message.setCreatedAt(Instant.ofEpochMilli(task.getCreatedAtEpochMillis()));
        return message;
    }
}
