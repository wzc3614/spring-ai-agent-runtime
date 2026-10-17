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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具治理元数据注册表的默认实现。默认元数据：低风险、非幂等（保守，安全优先）。
 */
public class DefaultToolMetaRegistry implements ToolMetaRegistry {

    private final Map<String, ToolMeta> metas = new ConcurrentHashMap<>();

    @Override
    public ToolMeta getMeta(String toolName) {
        if (toolName == null) {
            return defaultMeta();
        }
        return metas.getOrDefault(toolName, defaultMeta());
    }

    @Override
    public void register(ToolMeta meta) {
        metas.put(meta.getToolName(), meta);
    }

    @Override
    public ToolMeta defaultMeta() {
        ToolMeta meta = new ToolMeta();
        meta.setToolName("__default__");
        meta.setRiskLevel(RiskLevelEnum.LOW);
        meta.setIdempotent(false);
        return meta;
    }
}
