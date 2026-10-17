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
 * 工具压缩策略注册表。按工具名查找策略，未注册时返回默认策略。
 */
public interface ToolCompactionPolicyRegistry {

    /**
     * 返回一个默认注册表实例（默认策略：可压缩、完结即清）。
     *
     * @return 默认注册表
     */
    static ToolCompactionPolicyRegistry withDefaults() {
        return new DefaultToolCompactionPolicyRegistry();
    }

    /**
     * 查询某工具的压缩策略。
     *
     * @param toolName 工具名称
     * @return 命中策略，未注册则返回默认策略
     */
    ToolCompactionRule getRule(String toolName);

    /**
     * 注册工具压缩策略。
     *
     * @param rule 压缩策略
     */
    void register(ToolCompactionRule rule);

    /**
     * 返回默认压缩策略。
     *
     * @return 默认策略
     */
    ToolCompactionRule defaultRule();
}
