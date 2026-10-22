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

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 演示用 badcase 工具：用于端到端验证工具调用的超时、重试、失败隔离能力。
 *
 * <p>对应治理元数据（idempotent / maxAttempts / timeoutMs）在 {@link DemoAgentService}
 * 构造时注册到 {@code ToolMetaRegistry}：
 * <ul>
 *   <li>{@code flaky_idempotent_lookup}：幂等 + maxAttempts=3，每 3 次成功 1 次（前 2 次抛 IO），
 *       验证 TRANSIENT 自动重试。</li>
 *   <li>{@code slow_timeout_tool}：超时 1s，实际睡 3s，验证单调用超时 + 中断。</li>
 *   <li>{@code terminal_bad_param}：幂等 + maxAttempts=3 但抛 IllegalArgumentException（TERMINAL），
 *       验证终态错误不重试。</li>
 *   <li>{@code ambiguous_non_idempotent_charge}：非幂等 + 抛 SocketTimeoutException（AMBIGUOUS），
 *       验证非幂等超时绝不重试。</li>
 * </ul>
 */
@Component
public class BadCaseTool {

    /** flaky 工具的调用计数，每 3 次成功 1 次（前 2 次抛 IO），用于触发重试 */
    private final AtomicInteger flakyCounter = new AtomicInteger(0);

    /**
     * 不稳定的幂等查询：前 2 次抛 IOException，第 3 次成功。
     *
     * @param key 查询键
     * @return 查询结果
     */
    @Tool(name = "flaky_idempotent_lookup",
            description = "从不稳定的下游查询一个 key，会间歇性抛出 IO 错误。该操作幂等，失败可安全重试。")
    public String flakyIdempotentLookup(String key) throws IOException {
        int attempt = flakyCounter.incrementAndGet();
        if (attempt % 3 != 0) {
            throw new IOException("downstream temporarily unavailable (attempt " + attempt + ")");
        }
        return "{\"key\":\"" + key + "\",\"value\":\"ok-after-retry\",\"attempts\":" + attempt + "}";
    }

    /**
     * 慢工具：睡 3 秒模拟 hang 死的下游，配合 1s 超时验证单调用超时。
     *
     * @param key 查询键
     * @return 查询结果（正常路径不会返回，会被超时中断）
     */
    @Tool(name = "slow_timeout_tool",
            description = "一个会睡眠 3 秒的工具，用于模拟 hang 死的下游，测试单次调用的超时机制。")
    public String slowTimeoutTool(String key) {
        try {
            Thread.sleep(3000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("slow tool interrupted by timeout", e);
        }
        return "{\"key\":\"" + key + "\",\"value\":\"should-never-see-this\"}";
    }

    /**
     * 终态错误工具：永远抛 IllegalArgumentException（TERMINAL），即便配置了重试也不应重试。
     *
     * @param input 输入
     * @return 不会返回
     */
    @Tool(name = "terminal_bad_param",
            description = "一个永远以非法参数错误拒绝输入的工具，用于测试终态失败（不会重试）。")
    public String terminalBadParam(String input) {
        throw new IllegalArgumentException("invalid param: " + input);
    }

    /**
     * 非幂等扣款工具：抛 SocketTimeoutException（AMBIGUOUS），非幂等绝不自动重试。
     *
     * @param orderId 订单 ID
     * @return 不会返回
     */
    @Tool(name = "ambiguous_non_idempotent_charge",
            description = "一个非幂等的扣款工具，会对下游超时，用于测试结果不确定的失败（绝不自动重试）。")
    public String ambiguousNonIdempotentCharge(String orderId) throws SocketTimeoutException {
        throw new SocketTimeoutException("charge downstream timeout for order " + orderId);
    }
}
