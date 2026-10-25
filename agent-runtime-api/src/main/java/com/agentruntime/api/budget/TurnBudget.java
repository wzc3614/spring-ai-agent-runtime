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
package com.agentruntime.api.budget;

/**
 * 单轮 ReAct 预算。限制一次用户请求内的模型调用次数，以及这些调用累计消耗的 token。
 */
public class TurnBudget {

    /** 单轮最多模型调用次数 */
    private int maxModelCalls = TurnBudgetConstants.DEFAULT_MAX_MODEL_CALLS;

    /** 单轮累计 token 上限 */
    private int maxTurnTokens = TurnBudgetConstants.DEFAULT_MAX_TURN_TOKENS;

    /**
     * 默认预算：6 步、64000 token。
     *
     * @return 默认预算
     */
    public static TurnBudget defaults() {
        return new TurnBudget();
    }

    public int getMaxModelCalls() {
        return maxModelCalls;
    }

    public void setMaxModelCalls(int maxModelCalls) {
        this.maxModelCalls = maxModelCalls;
    }

    public int getMaxTurnTokens() {
        return maxTurnTokens;
    }

    public void setMaxTurnTokens(int maxTurnTokens) {
        this.maxTurnTokens = maxTurnTokens;
    }
}
