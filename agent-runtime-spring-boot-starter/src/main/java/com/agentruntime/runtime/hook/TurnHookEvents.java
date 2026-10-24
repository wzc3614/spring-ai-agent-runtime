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
package com.agentruntime.runtime.hook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把轮次打断结果写成观测事件载荷。
 */
public final class TurnHookEvents {

    private static final Logger logger = LoggerFactory.getLogger(TurnHookEvents.class);

    private TurnHookEvents() {
    }

    /**
     * 序列化打断载荷。失败时返回空对象，不中断收尾。
     *
     * @param objectMapper JSON 序列化器
     * @param result       门闩结果
     * @return JSON 字符串
     */
    public static String payload(ObjectMapper objectMapper, TurnHookResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TurnHookConstants.PAYLOAD_PHASE, result.getPhase().getCode());
        body.put(TurnHookConstants.PAYLOAD_REASON, result.getReasonCode());
        body.put(TurnHookConstants.PAYLOAD_REPLY, result.getReply());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException ex) {
            logger.warn("turn interrupt payload serialization failed");
            return TurnHookConstants.EMPTY_PAYLOAD;
        }
    }
}
