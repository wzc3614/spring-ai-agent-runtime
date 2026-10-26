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

import com.agentruntime.api.domain.AgentSession;
import com.agentruntime.api.domain.TurnRecord;
import com.agentruntime.api.exception.TurnAccessException;
import com.agentruntime.api.repository.SessionRepository;
import com.agentruntime.api.turn.TurnExecutionConstants;
import com.agentruntime.api.turn.TurnStore;
import com.agentruntime.runtime.turn.TurnExecutionEngine;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

/**
 * 断线后续传。不新开一轮，只按 afterSeq 跟随已经落盘的帧。
 */
@RestController
public class TurnReplayController {

    private final TurnExecutionEngine turnExecutionEngine;

    private final TurnStore turnStore;

    private final SessionRepository sessionRepository;

    public TurnReplayController(TurnExecutionEngine turnExecutionEngine, TurnStore turnStore,
                                SessionRepository sessionRepository) {
        this.turnExecutionEngine = turnExecutionEngine;
        this.turnStore = turnStore;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 从 afterSeq 之后回放，轮次还在跑就继续跟随。
     *
     * @param sessionId 会话
     * @param turnId    轮次
     * @param afterSeq  客户端已经收到的序号
     * @param uid       用户，用来核对会话归属
     * @return SSE
     */
    @GetMapping(value = TurnExecutionConstants.REPLAY_PATH, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> events(@PathVariable String sessionId,
                                                 @PathVariable String turnId,
                                                 @RequestParam(name = TurnExecutionConstants.PARAM_AFTER_SEQ,
                                                         defaultValue = "0") long afterSeq,
                                                 @RequestParam(name = TurnExecutionConstants.PARAM_UID,
                                                         required = false) String uid) {
        AgentSession session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new TurnAccessException(TurnExecutionConstants.ERROR_TURN_NOT_FOUND,
                        "session not found"));
        String owner = session.getUserId();
        if (StringUtils.isNotBlank(owner) && !owner.equals(StringUtils.trimToEmpty(uid))) {
            throw new TurnAccessException(TurnExecutionConstants.ERROR_SESSION_UID_MISMATCH,
                    "session does not belong to this uid");
        }
        TurnRecord turn = turnStore.find(turnId)
                .orElseThrow(() -> new TurnAccessException(TurnExecutionConstants.ERROR_TURN_NOT_FOUND,
                        "turn not found"));
        if (!sessionId.equals(turn.getSessionId())) {
            throw new TurnAccessException(TurnExecutionConstants.ERROR_TURN_NOT_IN_SESSION,
                    "turn does not belong to session");
        }
        return turnExecutionEngine.follow(turnId, afterSeq)
                .map(frame -> ServerSentEvent.builder(frame.getText())
                        .id(Long.toString(frame.getSeq()))
                        .event(frame.getType().getCode())
                        .build());
    }
}
