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
package com.agentruntime.api.domain;

import java.util.List;

/**
 * 第一层零压缩结构化提取结果：从待压缩 Turn 中精确抽取事实、决策、约束、目标、未完成项。
 *
 * <p>这些信息不需要"总结"，需要的是精确持久化，作为后续增量摘要的输入。
 * 复用 {@link SummaryContent.Decision} 表达决策。
 */
public class StructuredExtraction {

    /** 精确事实 */
    private List<String> facts;

    /** 重大决策及理由 */
    private List<SummaryContent.Decision> decisions;

    /** 硬性约束 */
    private List<String> constraints;

    /** 目标 */
    private List<String> goals;

    /** 未完成项 */
    private List<String> unresolved;

    public List<String> getFacts() {
        return facts;
    }

    public void setFacts(List<String> facts) {
        this.facts = facts;
    }

    public List<SummaryContent.Decision> getDecisions() {
        return decisions;
    }

    public void setDecisions(List<SummaryContent.Decision> decisions) {
        this.decisions = decisions;
    }

    public List<String> getConstraints() {
        return constraints;
    }

    public void setConstraints(List<String> constraints) {
        this.constraints = constraints;
    }

    public List<String> getGoals() {
        return goals;
    }

    public void setGoals(List<String> goals) {
        this.goals = goals;
    }

    public List<String> getUnresolved() {
        return unresolved;
    }

    public void setUnresolved(List<String> unresolved) {
        this.unresolved = unresolved;
    }
}
