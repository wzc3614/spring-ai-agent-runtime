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

import com.agentruntime.runtime.hook.TurnHook;
import com.agentruntime.runtime.hook.TurnHookContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 演示用轮次钩子，模拟对客风控的两段检查。
 *
 * <p>用户输入包含「敏感词演练」时，在 Skill 准备期间直接结束本轮。
 * 包含「风控演练」时，并行发起一次延迟返回的风控调用，在组装 prompt 前取结果并结束本轮。
 * 两条标记都不包含时放行，继续走模型和工具。
 */
public class DemoRiskTurnHook implements TurnHook {

    private static final Logger logger = LoggerFactory.getLogger(DemoRiskTurnHook.class);

    /** 第一阶段本地敏感词标记 */
    static final String SENSITIVE_MARKER = "敏感词演练";

    /** 第二阶段异步风控标记 */
    static final String RISK_MARKER = "风控演练";

    private static final String REASON_SENSITIVE = "SENSITIVE_WORD";

    private static final String REASON_RISK = "RISK_HIT";

    private static final String REASON_RISK_TIMEOUT = "RISK_TIMEOUT";

    /** 敏感词命中后返回给调用方的回复 */
    static final String SENSITIVE_REPLY = "输入包含敏感内容，本轮已结束，没有调用模型。";

    /** 风控命中后返回给调用方的回复 */
    static final String RISK_REPLY = "风控未通过，本轮已在组装提示词前结束，没有调用模型。";

    private static final String RISK_TIMEOUT_REPLY = "风控服务超时，本轮已结束。";

    /** 挂在轮次上下文上的风控 Future */
    private static final String RISK_FUTURE_KEY = "demoRiskFuture";

    /** 模拟风控服务耗时 */
    private static final long RISK_LATENCY_MILLIS = 1200L;

    /** 第二阶段等待风控结果的上限 */
    private static final long RISK_WAIT_MILLIS = 3000L;

    @Override
    public void onPrepare(TurnHookContext context) {
        String userText = context.getUserText();
        if (!StringUtils.hasText(userText)) {
            return;
        }
        if (userText.contains(SENSITIVE_MARKER)) {
            context.interrupt(REASON_SENSITIVE, SENSITIVE_REPLY);
            return;
        }
        if (userText.contains(RISK_MARKER)) {
            context.getAttributes().put(RISK_FUTURE_KEY, checkRiskAsync(context.getSessionId()));
        }
    }

    @Override
    public void beforePrompt(TurnHookContext context) {
        Object pending = context.getAttributes().get(RISK_FUTURE_KEY);
        if (!(pending instanceof CompletableFuture<?> future)) {
            return;
        }
        awaitRisk(context, future);
    }

    /**
     * 模拟异步风控调用。只把 Future 交给第二阶段，不在这里等待。
     */
    private CompletableFuture<Boolean> checkRiskAsync(String sessionId) {
        logger.info("demo risk check scheduled, sessionId={}", sessionId);
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        Thread.startVirtualThread(() -> completeAfterDelay(sessionId, future));
        return future;
    }

    private void completeAfterDelay(String sessionId, CompletableFuture<Boolean> future) {
        try {
            Thread.sleep(RISK_LATENCY_MILLIS);
            logger.info("demo risk check hit, sessionId={}", sessionId);
            future.complete(Boolean.TRUE);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            future.completeExceptionally(ex);
        }
    }

    private void awaitRisk(TurnHookContext context, CompletableFuture<?> future) {
        try {
            Object hit = future.get(RISK_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            if (Boolean.TRUE.equals(hit)) {
                context.interrupt(REASON_RISK, RISK_REPLY);
            }
        } catch (TimeoutException ex) {
            logger.warn("demo risk check timed out, sessionId={}", context.getSessionId());
            context.interrupt(REASON_RISK_TIMEOUT, RISK_TIMEOUT_REPLY);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("demo risk check interrupted", ex);
        } catch (ExecutionException ex) {
            throw new IllegalStateException("demo risk check failed", ex);
        }
    }
}
