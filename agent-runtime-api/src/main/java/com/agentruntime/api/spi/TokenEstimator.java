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
package com.agentruntime.api.spi;

import com.agentruntime.api.domain.AgentMessage;

import java.util.List;

/**
 * 本地 token 估算 SPI。不调用远程 tokenizer，靠经验系数估算，
 * 由调用方用阈值余量兜住估算误差。默认实现在 memory 模块。
 */
public interface TokenEstimator {

    /**
     * 估算普通文本的 token 数。
     *
     * @param text 文本
     * @return token 估算值
     */
    int estimate(String text);

    /**
     * 估算 JSON 的 token 数（在普通估算基础上上浮）。
     *
     * @param json JSON 文本
     * @return token 估算值
     */
    int estimateJson(String json);

    /**
     * 估算一组消息的 token 总量。
     *
     * @param messages 消息列表
     * @return token 估算值
     */
    int estimateMessages(List<AgentMessage> messages);
}
