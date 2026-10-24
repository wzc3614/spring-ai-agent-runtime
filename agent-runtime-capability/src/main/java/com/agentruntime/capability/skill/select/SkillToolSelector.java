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
package com.agentruntime.capability.skill.select;

import com.agentruntime.api.capability.SkillDescriptor;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 本轮工具子集：未被任何 Skill 引用的工具常开；被引用的工具只在所属 Skill 命中时放行。
 */
public class SkillToolSelector {

    /**
     * 选择本轮工具。
     *
     * @param allTools   已包装的全部工具
     * @param registered 当前 Agent 可见 Skill
     * @param selected   本轮命中的 Skill
     * @return 去重后的工具列表
     */
    public List<ToolCallback> select(List<ToolCallback> allTools, List<SkillDescriptor> registered,
                                     List<SkillDescriptor> selected) {
        if (CollectionUtils.isEmpty(allTools)) {
            return List.of();
        }
        Set<String> gated = toolRefs(registered);
        Set<String> allowed = toolRefs(selected);
        List<ToolCallback> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ToolCallback tool : allTools) {
            String name = tool.getToolDefinition().name();
            if (!seen.add(name)) {
                continue;
            }
            if (!gated.contains(name) || allowed.contains(name)) {
                result.add(tool);
            }
        }
        return result;
    }

    /**
     * 本轮实际放行、且属于命中 Skill 的工具名。
     *
     * @param tools    本轮工具
     * @param selected 命中的 Skill
     * @return 工具名
     */
    public List<String> activatedToolNames(List<ToolCallback> tools, List<SkillDescriptor> selected) {
        Set<String> skillTools = toolRefs(selected);
        List<String> names = new ArrayList<>();
        if (CollectionUtils.isEmpty(tools)) {
            return names;
        }
        for (ToolCallback tool : tools) {
            String name = tool.getToolDefinition().name();
            if (skillTools.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private Set<String> toolRefs(List<SkillDescriptor> descriptors) {
        Set<String> names = new HashSet<>();
        if (CollectionUtils.isEmpty(descriptors)) {
            return names;
        }
        for (SkillDescriptor descriptor : descriptors) {
            if (!CollectionUtils.isEmpty(descriptor.getToolRefs())) {
                names.addAll(descriptor.getToolRefs());
            }
        }
        return names;
    }
}
