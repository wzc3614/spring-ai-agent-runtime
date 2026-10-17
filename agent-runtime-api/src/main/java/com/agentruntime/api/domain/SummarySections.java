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

/**
 * 九段式摘要的各段内容，对应 agent_summary_snapshot.sections JSONB。
 * 每段为一段自然语言文本；预算紧张时按 P3 -> P0 方向依次裁剪，P0 两段永不省略。
 */
public class SummarySections {

    /** P0 当前进度 */
    private String currentState;

    /** P0 待办 */
    private String pendingTasks;

    /** P1 用户意图 */
    private String userIntent;

    /** P1 关键决策 */
    private String keyDecisions;

    /** P1 关键事实 */
    private String importantFacts;

    /** P2 已完成工作 */
    private String completedWorks;

    /** P2 错误与纠正 */
    private String errorsAndCorrections;

    /** P3 问题解决过程 */
    private String problemSolving;

    /** P3 所有用户消息 */
    private String allUserMessages;

    public String getCurrentState() {
        return currentState;
    }

    public void setCurrentState(String currentState) {
        this.currentState = currentState;
    }

    public String getPendingTasks() {
        return pendingTasks;
    }

    public void setPendingTasks(String pendingTasks) {
        this.pendingTasks = pendingTasks;
    }

    public String getUserIntent() {
        return userIntent;
    }

    public void setUserIntent(String userIntent) {
        this.userIntent = userIntent;
    }

    public String getKeyDecisions() {
        return keyDecisions;
    }

    public void setKeyDecisions(String keyDecisions) {
        this.keyDecisions = keyDecisions;
    }

    public String getImportantFacts() {
        return importantFacts;
    }

    public void setImportantFacts(String importantFacts) {
        this.importantFacts = importantFacts;
    }

    public String getCompletedWorks() {
        return completedWorks;
    }

    public void setCompletedWorks(String completedWorks) {
        this.completedWorks = completedWorks;
    }

    public String getErrorsAndCorrections() {
        return errorsAndCorrections;
    }

    public void setErrorsAndCorrections(String errorsAndCorrections) {
        this.errorsAndCorrections = errorsAndCorrections;
    }

    public String getProblemSolving() {
        return problemSolving;
    }

    public void setProblemSolving(String problemSolving) {
        this.problemSolving = problemSolving;
    }

    public String getAllUserMessages() {
        return allUserMessages;
    }

    public void setAllUserMessages(String allUserMessages) {
        this.allUserMessages = allUserMessages;
    }
}
