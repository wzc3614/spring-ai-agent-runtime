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
package com.agentruntime.turn;

import com.agentruntime.api.exception.TurnAccessException;
import com.agentruntime.api.exception.TurnBusyException;
import com.agentruntime.api.turn.TurnExecutionConstants;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把轮次冲突和拒绝翻译成 HTTP。同步调用撞上未完成轮时返回 409 和已有 turnId。
 */
@RestControllerAdvice
public class TurnExecutionExceptionHandler {

    /**
     * @param exception 未完成轮
     * @return 409，响应头带 turnId
     */
    @ExceptionHandler(TurnBusyException.class)
    public ResponseEntity<String> busy(TurnBusyException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(TurnExecutionConstants.HEADER_TURN_ID, exception.getTurnId())
                .body(exception.getTurnId());
    }

    /**
     * @param exception 归属或参数拒绝
     * @return 400，正文是错误码
     */
    @ExceptionHandler(TurnAccessException.class)
    public ResponseEntity<String> rejected(TurnAccessException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(exception.getErrorCode());
    }
}
