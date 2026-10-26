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
package com.agentruntime.demo;

import com.agentruntime.api.turn.TurnExecutionConstants;
import com.agentruntime.runtime.turn.TurnHandle;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;

/**
 * 演示 REST 端点。
 */
@RestController
public class DemoController {

    private final DemoAgentService agentService;

    public DemoController(DemoAgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * 对话端点。
     *
     * @param uid       用户 ID
     * @param sessionId  会话 ID
     * @param message    用户消息
     * @return 回复
     */
    @PostMapping("/chat")
    public String chat(@RequestParam String uid,
                       @RequestParam String sessionId,
                       @RequestParam String message) {
        return agentService.chat(uid, sessionId, message);
    }

    /**
     * 流式对话。响应头 {@code X-Turn-Id} 是这一轮，正文按 seq 推送帧。
     *
     * @param uid       用户 ID
     * @param sessionId  会话 ID
     * @param message    用户消息
     * @return SSE
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<Flux<ServerSentEvent<String>>> chatStream(@RequestParam String uid,
                                                                    @RequestParam String sessionId,
                                                                    @RequestParam String message) {
        TurnHandle handle = agentService.chatStream(uid, sessionId, message);
        Flux<ServerSentEvent<String>> body = handle.frames().map(frame -> ServerSentEvent.builder(frame.getText())
                .id(Long.toString(frame.getSeq()))
                .event(frame.getType().getCode())
                .build());
        return ResponseEntity.ok()
                .header(TurnExecutionConstants.HEADER_TURN_ID, handle.getTurnId())
                .header(TurnExecutionConstants.HEADER_TURN_REUSED, Boolean.toString(handle.isReused()))
                .body(body);
    }

    /**
     * 结束会话端点。
     *
     * @param sessionId 会话 ID
     * @return 结果
     */
    @PostMapping("/chat/close")
    public String close(@RequestParam String sessionId) {
        agentService.close(sessionId);
        return "closed";
    }
}
