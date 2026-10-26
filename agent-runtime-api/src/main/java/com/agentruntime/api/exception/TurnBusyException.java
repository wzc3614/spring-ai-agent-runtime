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
package com.agentruntime.api.exception;

/**
 * 同步调用撞上同一会话的未完成轮。调用方用 {@link #turnId} 去挂回放，而不是再开一轮。
 */
public class TurnBusyException extends AgentRuntimeException {

    private static final long serialVersionUID = 1L;

    /** 已经在执行的轮次 */
    private final String turnId;

    /**
     * @param turnId 未完成轮次
     */
    public TurnBusyException(String turnId) {
        super("turn still running, turnId=" + turnId);
        this.turnId = turnId;
    }

    public String getTurnId() {
        return turnId;
    }
}
