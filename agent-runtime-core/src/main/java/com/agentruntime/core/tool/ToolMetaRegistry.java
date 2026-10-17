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

/**
 * 工具治理元数据注册表。按 toolName 查询，未注册时返回默认元数据。
 */
public interface ToolMetaRegistry {

    /**
     * 查询某工具的治理元数据。
     *
     * @param toolName 工具名称
     * @return 命中元数据，未注册则返回默认
     */
    ToolMeta getMeta(String toolName);

    /**
     * 注册工具治理元数据。
     *
     * @param meta 元数据
     */
    void register(ToolMeta meta);

    /**
     * 返回默认元数据（保守：低风险、非幂等）。
     *
     * @return 默认元数据
     */
    ToolMeta defaultMeta();
}
