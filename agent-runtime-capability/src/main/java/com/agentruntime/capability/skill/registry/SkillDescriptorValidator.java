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
package com.agentruntime.capability.skill.registry;

import com.agentruntime.api.capability.SkillDescriptor;
import com.agentruntime.capability.skill.SkillConstants;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Skill 注册校验。格式不合法直接丢弃；工具引用是否悬空由调用方按 Agent 工具集另行判断。
 */
public class SkillDescriptorValidator {

    private static final Logger logger = LoggerFactory.getLogger(SkillDescriptorValidator.class);

    private static final Pattern SKILL_ID = Pattern.compile(SkillConstants.SKILL_ID_PATTERN);

    /**
     * 校验必填字段和 skillId 形态。
     *
     * @param descriptor 描述符
     * @return 格式合法返回 true
     */
    public boolean isWellFormed(SkillDescriptor descriptor) {
        if (descriptor == null || StringUtils.isBlank(descriptor.getSkillId())) {
            logger.warn("reject skill: missing skillId");
            return false;
        }
        if (!SKILL_ID.matcher(descriptor.getSkillId()).matches()) {
            logger.warn("reject skill: illegal skillId={}", descriptor.getSkillId());
            return false;
        }
        if (StringUtils.isBlank(descriptor.getName())
                || StringUtils.isBlank(descriptor.getDescription())
                || StringUtils.isBlank(descriptor.getInstructionRef())) {
            logger.warn("reject skill: missing required field, skillId={}", descriptor.getSkillId());
            return false;
        }
        return true;
    }

    /**
     * 启用中的 Skill 必须能解析全部 toolRefs。空引用视为纯指令 Skill，合法。
     *
     * @param descriptor 描述符
     * @param toolNames  当前 Agent 的工具名
     * @return 全部可解析返回 true
     */
    public boolean hasResolvableTools(SkillDescriptor descriptor, Set<String> toolNames) {
        if (descriptor == null || CollectionUtils.isEmpty(descriptor.getToolRefs())) {
            return true;
        }
        if (CollectionUtils.isEmpty(toolNames)) {
            return false;
        }
        for (String toolRef : descriptor.getToolRefs()) {
            if (!toolNames.contains(toolRef)) {
                return false;
            }
        }
        return true;
    }
}
