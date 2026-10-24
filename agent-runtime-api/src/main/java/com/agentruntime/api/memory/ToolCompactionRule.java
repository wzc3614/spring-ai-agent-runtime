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
package com.agentruntime.api.memory;

import com.agentruntime.api.enums.ToolCompactionStrategyEnum;

/**
 * 单个工具的结果压缩策略。未注册的工具走默认规则：查询类，下一条用户消息起过期。
 */
public class ToolCompactionRule {

    /** 工具名称 */
    private String toolName;

    /** 策略 */
    private ToolCompactionStrategyEnum strategy = ToolCompactionStrategyEnum.QUERY;

    /** 查询类：之后再出现多少条用户消息才清除原文 */
    private int expireAfterTurns = ToolCompactionConstants.DEFAULT_QUERY_EXPIRE_TURNS;

    /** 搜索类：注入上下文的摘要字符上限 */
    private int summaryMaxChars = ToolCompactionConstants.DEFAULT_SUMMARY_MAX_CHARS;

    /** 自定义策略编码，仅 CUSTOM 使用 */
    private String compactorCode;

    /**
     * 查询类。expireAfterTurns 为 1 时，下一条用户消息起不再携带原文。
     *
     * @param toolName         工具名称
     * @param expireAfterTurns 过期所需的后续用户消息数
     * @return 规则
     */
    public static ToolCompactionRule query(String toolName, int expireAfterTurns) {
        ToolCompactionRule rule = new ToolCompactionRule();
        rule.setToolName(toolName);
        rule.setStrategy(ToolCompactionStrategyEnum.QUERY);
        rule.setExpireAfterTurns(expireAfterTurns);
        return rule;
    }

    /**
     * 操作类，原文完整保留。
     *
     * @param toolName 工具名称
     * @return 规则
     */
    public static ToolCompactionRule retain(String toolName) {
        ToolCompactionRule rule = new ToolCompactionRule();
        rule.setToolName(toolName);
        rule.setStrategy(ToolCompactionStrategyEnum.RETAIN);
        return rule;
    }

    /**
     * 搜索类。当前轮保留原文，之后换成不超过 summaryMaxChars 的确定性摘要。
     *
     * @param toolName        工具名称
     * @param summaryMaxChars 摘要字符上限
     * @return 规则
     */
    public static ToolCompactionRule summarize(String toolName, int summaryMaxChars) {
        ToolCompactionRule rule = new ToolCompactionRule();
        rule.setToolName(toolName);
        rule.setStrategy(ToolCompactionStrategyEnum.SUMMARIZE);
        rule.setSummaryMaxChars(summaryMaxChars);
        return rule;
    }

    /**
     * 自定义渲染器。code 对应 {@code ToolResultCompactor#code()}。
     *
     * @param toolName      工具名称
     * @param compactorCode 渲染器编码
     * @return 规则
     */
    public static ToolCompactionRule custom(String toolName, String compactorCode) {
        ToolCompactionRule rule = new ToolCompactionRule();
        rule.setToolName(toolName);
        rule.setStrategy(ToolCompactionStrategyEnum.CUSTOM);
        rule.setCompactorCode(compactorCode);
        return rule;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public ToolCompactionStrategyEnum getStrategy() {
        return strategy;
    }

    public void setStrategy(ToolCompactionStrategyEnum strategy) {
        this.strategy = strategy;
    }

    public int getExpireAfterTurns() {
        return expireAfterTurns;
    }

    public void setExpireAfterTurns(int expireAfterTurns) {
        this.expireAfterTurns = expireAfterTurns;
    }

    public int getSummaryMaxChars() {
        return summaryMaxChars;
    }

    public void setSummaryMaxChars(int summaryMaxChars) {
        this.summaryMaxChars = summaryMaxChars;
    }

    public String getCompactorCode() {
        return compactorCode;
    }

    public void setCompactorCode(String compactorCode) {
        this.compactorCode = compactorCode;
    }
}
