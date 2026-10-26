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
package com.agentruntime.core.tool;

import com.agentruntime.api.domain.ToolCall;
import com.agentruntime.api.enums.FailureCategoryEnum;
import com.agentruntime.api.spi.ToolFailureClassifier;
import com.agentruntime.core.springai.BaseToolCallbackDecorator;
import com.agentruntime.core.springai.TurnToolStartHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.ToolExecutionException;

/**
 * 治理式 ToolCallback 装饰器：在 {@link ToolFailureClassifier} 分类基础上对瞬时失败做自动重试。
 *
 * <p>重试规则（与 {@link DefaultToolFailureClassifier} 语义一致）：
 * <ul>
 *   <li>TRANSIENT + 幂等 + 还有剩余尝试次数 → 退避后重试。</li>
 *   <li>AMBIGUOUS（非幂等 + 超时类）→ 绝不自动重试。</li>
 *   <li>TERMINAL / 非幂等 / 重试耗尽 → 不再重试，把异常包装成 {@link ToolExecutionException} 抛出，
 *       交由 {@code ParallelToolCallingManager} 已有的 {@code ToolExecutionExceptionProcessor}
 *       转成字符串回填模型。</li>
 * </ul>
 *
 * <p>本装饰器不负责超时——单次调用的总墙钟超时由 {@code ParallelToolExecutor} 在外层按任务强制，
 * 超时后中断虚拟线程，本类的退避 {@code sleep} 与阻塞调用都会响应中断并尽快退出。
 */
public class GovernedToolCallback extends BaseToolCallbackDecorator {

    private static final Logger logger = LoggerFactory.getLogger(GovernedToolCallback.class);

    private final ToolMetaRegistry metaRegistry;

    private final ToolFailureClassifier classifier;

    private final ToolGovernanceDefaults defaults;

    public GovernedToolCallback(org.springframework.ai.tool.ToolCallback delegate,
            ToolMetaRegistry metaRegistry, ToolFailureClassifier classifier, ToolGovernanceDefaults defaults) {
        super(delegate);
        this.metaRegistry = metaRegistry;
        this.classifier = classifier;
        this.defaults = defaults;
    }

    @Override
    public String call(String toolInput) {
        return govern(toolInput, () -> delegate().call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext context) {
        return govern(toolInput, () -> delegate().call(toolInput, context));
    }

    /**
     * 执行带重试治理的单次工具调用。
     *
     * @param toolInput 工具入参
     * @param action    实际调用动作（委托内层装饰器/原生回调）
     * @return 工具结果字符串
     */
    private String govern(String toolInput, java.util.function.Supplier<String> action) {
        ToolDefinition toolDefinition = delegate().getToolDefinition();
        String toolName = toolDefinition.name();
        ToolMeta meta = metaRegistry.getMeta(toolName);
        TurnToolStartHook.record(meta.isIdempotent());
        int attempts = resolveAttempts(meta);
        BackoffPolicy backoff = resolveBackoff(meta);

        for (int i = 1; i <= attempts; i++) {
            try {
                return action.get();
            } catch (Throwable e) {
                FailureCategoryEnum category = classifier.classify(e, buildToolCallView(meta));
                if (category == FailureCategoryEnum.TRANSIENT && meta.isIdempotent() && i < attempts) {
                    logger.debug("tool [{}] attempt {}/{} failed with TRANSIENT, retrying", toolName, i, attempts);
                    sleepBeforeRetry(backoff, i);
                    continue;
                }
                logger.debug("tool [{}] attempt {}/{} failed with {}, not retrying", toolName, i, attempts, category);
                throwAsToolExecutionException(toolDefinition, e);
                // unreachable, satisfy compiler
                return "";
            }
        }
        // 循环正常结束不应发生（attempts>=1 时至少执行一次），防御性返回
        throw new IllegalStateException("tool [" + toolName + "] retry loop exited without result");
    }

    /** 退避等待，响应中断即放弃重试。 */
    private void sleepBeforeRetry(BackoffPolicy backoff, int attempt) {
        long delay = backoff.nextDelayMs(attempt);
        if (delay <= 0) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("tool retry backoff interrupted", e);
        }
    }

    /** 把失败异常包装成 Spring AI {@link ToolExecutionException} 抛出，交由上层 processor 处理。 */
    private void throwAsToolExecutionException(ToolDefinition toolDefinition, Throwable cause) {
        if (cause instanceof ToolExecutionException toolExecutionException) {
            throw toolExecutionException;
        }
        throw new ToolExecutionException(toolDefinition, cause);
    }

    private int resolveAttempts(ToolMeta meta) {
        Integer max = meta.getMaxAttempts();
        if (max != null && max >= 1) {
            return max;
        }
        return Math.max(1, defaults.getDefaultMaxAttempts());
    }

    private BackoffPolicy resolveBackoff(ToolMeta meta) {
        BackoffPolicy backoff = meta.getBackoff();
        return backoff != null ? backoff : defaults.getDefaultBackoff();
    }

    /** 构造分类器所需的 {@link ToolCall} 视图，仅填充分类依赖的 idempotent 等字段。 */
    private ToolCall buildToolCallView(ToolMeta meta) {
        ToolCall view = new ToolCall();
        view.setToolName(meta.getToolName());
        view.setIdempotent(meta.isIdempotent());
        view.setRiskLevel(meta.getRiskLevel());
        return view;
    }
}
