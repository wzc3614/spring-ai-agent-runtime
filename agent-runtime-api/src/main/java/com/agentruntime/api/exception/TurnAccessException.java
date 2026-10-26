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
 * 轮次请求被拒绝。{@link #errorCode} 给客户端，日志里不放用户原文。
 */
public class TurnAccessException extends AgentRuntimeException {

    private static final long serialVersionUID = 1L;

    /** 稳定错误码 */
    private final String errorCode;

    /**
     * @param errorCode 错误码
     * @param message   英文说明
     */
    public TurnAccessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
