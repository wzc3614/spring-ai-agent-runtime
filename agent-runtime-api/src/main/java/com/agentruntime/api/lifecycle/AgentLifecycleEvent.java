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
package com.agentruntime.api.lifecycle;

import java.time.Instant;

/**
 * 会话生命周期事件，用于在会话结束时协调各子系统清理。
 */
public class AgentLifecycleEvent {

    /** 会话 ID */
    private String sessionId;

    /** 事件类型 */
    private AgentLifecycleTypeEnum type;

    /** 发生时间 */
    private Instant occurredAt;

    /** 会话基本信息 */
    private SessionInfo session;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public AgentLifecycleTypeEnum getType() {
        return type;
    }

    public void setType(AgentLifecycleTypeEnum type) {
        this.type = type;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public void setOccurredAt(Instant occurredAt) {
        this.occurredAt = occurredAt;
    }

    public SessionInfo getSession() {
        return session;
    }

    public void setSession(SessionInfo session) {
        this.session = session;
    }
}
