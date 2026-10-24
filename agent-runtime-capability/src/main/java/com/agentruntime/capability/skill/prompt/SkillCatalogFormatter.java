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
package com.agentruntime.capability.skill.prompt;

import com.agentruntime.api.capability.SkillDescriptor;
import com.agentruntime.capability.skill.SkillConstants;

import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 把 Skill 的 name、description、notFor 格式化成一段紧凑目录。工具名和指令正文不进入目录。
 */
public class SkillCatalogFormatter {

    /**
     * 格式化目录。
     *
     * @param skills 可见 Skill
     * @return 目录文本；没有 Skill 时返回空串
     */
    public String format(List<SkillDescriptor> skills) {
        if (CollectionUtils.isEmpty(skills)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append(SkillConstants.CATALOG_HEADER).append('\n');
        for (SkillDescriptor skill : skills) {
            builder.append("- ").append(skill.getName()).append(": ").append(skill.getDescription()).append('\n');
            if (!CollectionUtils.isEmpty(skill.getNotFor())) {
                builder.append("  ").append(SkillConstants.NOT_FOR_LABEL)
                        .append(String.join(", ", skill.getNotFor())).append('\n');
            }
        }
        return builder.toString();
    }
}
