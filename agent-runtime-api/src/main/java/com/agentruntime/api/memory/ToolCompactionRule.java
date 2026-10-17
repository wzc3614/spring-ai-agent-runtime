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

/**
 * 单个工具的结果微压缩策略。关键操作永不压缩，查询类按轮次过期清理。
 */
public class ToolCompactionRule {

    /** 工具名称 */
    private String toolName;

    /** 是否永不压缩（关键操作置 true） */
    private boolean neverCompact;

    /** 完结多少轮次后清理（查询类，0 表示立即清理） */
    private int expireAfterTurns;

    /**
     * 工厂方法：构建一个可清理的策略（neverCompact=false，完结即清）。
     *
     * @param toolName 工具名称
     * @return 可清理策略
     */
    public static ToolCompactionRule clearable(String toolName) {
        ToolCompactionRule rule = new ToolCompactionRule();
        rule.setToolName(toolName);
        rule.setNeverCompact(false);
        rule.setExpireAfterTurns(0);
        return rule;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public boolean isNeverCompact() {
        return neverCompact;
    }

    public void setNeverCompact(boolean neverCompact) {
        this.neverCompact = neverCompact;
    }

    public int getExpireAfterTurns() {
        return expireAfterTurns;
    }

    public void setExpireAfterTurns(int expireAfterTurns) {
        this.expireAfterTurns = expireAfterTurns;
    }
}
