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

import com.agentruntime.api.memory.CompactConfig;

/**
 * 动态预算计算器。对应设计文档 4.1.4 的预算公式：
 * <pre>
 * 有效窗口   = 上下文窗口 - 预留输出 - 安全缓冲
 * 历史预算   = 有效窗口 - 固定开销
 * 预估总量   = 固定开销 + 摘要tokens + 历史tokens
 * 触发压缩   ⇔ 预估总量 > 有效窗口 × 阈值
 * </pre>
 */
public class BudgetCalculator {

    /**
     * 计算预算。
     *
     * @param config        压缩配置
     * @param fixedOverhead  固定开销（系统提示词 + 工具 Schema + 当前输入）
     * @param summaryTokens  现有摘要 token
     * @param historyTokens  历史 token
     * @return 预算结果
     */
    public BudgetResult compute(CompactConfig config, int fixedOverhead, int summaryTokens, int historyTokens) {
        int effectiveWindow = config.getContextWindow() - config.getReservedOutput() - config.getSafetyBuffer();
        int historyBudget = effectiveWindow - fixedOverhead;
        int estimatedTotal = fixedOverhead + summaryTokens + historyTokens;
        boolean trigger = estimatedTotal > effectiveWindow * config.getThreshold();

        BudgetResult result = new BudgetResult();
        result.setEffectiveWindow(effectiveWindow);
        result.setHistoryBudget(historyBudget);
        result.setEstimatedTotal(estimatedTotal);
        result.setTriggerCompaction(trigger);
        return result;
    }
}
