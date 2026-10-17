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

/**
 * 动态预算计算结果。
 */
public class BudgetResult {

    /** 有效窗口 = 上下文窗口 - 预留输出 - 安全缓冲 */
    private int effectiveWindow;

    /** 历史预算 = 有效窗口 - 固定开销 */
    private int historyBudget;

    /** 预估总量 = 固定开销 + 摘要 + 历史 */
    private int estimatedTotal;

    /** 是否触发压缩 */
    private boolean triggerCompaction;

    public int getEffectiveWindow() {
        return effectiveWindow;
    }

    public void setEffectiveWindow(int effectiveWindow) {
        this.effectiveWindow = effectiveWindow;
    }

    public int getHistoryBudget() {
        return historyBudget;
    }

    public void setHistoryBudget(int historyBudget) {
        this.historyBudget = historyBudget;
    }

    public int getEstimatedTotal() {
        return estimatedTotal;
    }

    public void setEstimatedTotal(int estimatedTotal) {
        this.estimatedTotal = estimatedTotal;
    }

    public boolean isTriggerCompaction() {
        return triggerCompaction;
    }

    public void setTriggerCompaction(boolean triggerCompaction) {
        this.triggerCompaction = triggerCompaction;
    }
}
