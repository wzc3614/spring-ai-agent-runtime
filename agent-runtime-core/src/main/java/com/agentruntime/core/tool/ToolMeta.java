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

import com.agentruntime.api.enums.RiskLevelEnum;

/**
 * 工具治理元数据：风险等级 + 幂等性。供 Governed 装饰器（重试/HIL）使用，
 * 与 api.memory.ToolCompactionRule（压缩策略）职责不同、各自独立。
 */
public class ToolMeta {

    /** 工具名称 */
    private String toolName;

    /** 风险等级 */
    private RiskLevelEnum riskLevel;

    /** 是否幂等（决定失败能否自动重试） */
    private boolean idempotent;

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public RiskLevelEnum getRiskLevel() {
        return riskLevel;
    }

    public void setRiskLevel(RiskLevelEnum riskLevel) {
        this.riskLevel = riskLevel;
    }

    public boolean isIdempotent() {
        return idempotent;
    }

    public void setIdempotent(boolean idempotent) {
        this.idempotent = idempotent;
    }
}
