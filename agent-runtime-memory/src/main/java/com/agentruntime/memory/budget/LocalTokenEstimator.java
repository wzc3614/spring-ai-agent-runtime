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
package com.agentruntime.memory.budget;

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.spi.TokenEstimator;

import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 本地 token 估算器（默认实现）。不调用远程 tokenizer：
 * 英文约 4 字符/token，中文约 2 字符/token，JSON 在此基础上上浮 15%。
 * 由调用方用阈值余量兜住估算误差。
 */
public class LocalTokenEstimator implements TokenEstimator {

    /** JSON 上浮系数 */
    private static final double JSON_UPLOAD = 1.15D;

    /** 每条消息的角色框架开销（token） */
    private static final int PER_MESSAGE_OVERHEAD = 4;

    @Override
    public int estimate(String text) {
        if (StringUtils.isEmpty(text)) {
            return 0;
        }
        int cjk = 0;
        int other = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                cjk++;
            } else {
                other++;
            }
        }
        // 中文 2 字符/token，英文 4 字符/token
        double tokens = cjk / 2.0D + other / 4.0D;
        return (int) Math.ceil(tokens);
    }

    @Override
    public int estimateJson(String json) {
        return (int) Math.ceil(estimate(json) * JSON_UPLOAD);
    }

    @Override
    public int estimateMessages(List<AgentMessage> messages) {
        if (CollectionUtils.isEmpty(messages)) {
            return 0;
        }
        int total = 0;
        for (AgentMessage message : messages) {
            total += PER_MESSAGE_OVERHEAD;
            total += estimate(message.getContent());
        }
        return total;
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
