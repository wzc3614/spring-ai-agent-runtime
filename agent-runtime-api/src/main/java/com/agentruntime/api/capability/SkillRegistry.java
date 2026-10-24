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
package com.agentruntime.api.capability;

import java.util.List;
import java.util.Set;

/**
 * Skill 注册表。按来源优先级合并描述符，并按当前 Agent 的工具名拒绝悬空引用。
 * 路由前比对 revision，只有元数据变化时才重新加载。指令正文不在这里缓存。
 */
public interface SkillRegistry {

    /**
     * 返回当前 Agent 可见的 Skill：已启用，且 toolRefs 都能解析到 {@code toolNames}。
     * 同名以后来源覆盖先来源；覆盖项悬空时保留前一个来源的版本。
     *
     * @param toolNames 当前 Agent 已包装工具的名称
     * @return 可见 Skill 快照
     */
    List<SkillDescriptor> list(Set<String> toolNames);

    /**
     * 当前元数据 revision。
     *
     * @return revision 令牌
     */
    String revision();
}
