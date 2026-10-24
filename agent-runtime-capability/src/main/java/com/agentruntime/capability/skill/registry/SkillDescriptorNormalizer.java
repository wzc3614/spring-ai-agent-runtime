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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 Skill 描述上的空集合收成空列表 / 空 map，保证哈希输入稳定。
 */
public final class SkillDescriptorNormalizer {

    private SkillDescriptorNormalizer() {
    }

    public static void normalize(SkillDescriptor descriptor) {
        if (descriptor.getNotFor() == null) {
            descriptor.setNotFor(new ArrayList<>());
        }
        if (descriptor.getTriggers() == null) {
            descriptor.setTriggers(new ArrayList<>());
        }
        if (descriptor.getToolRefs() == null) {
            descriptor.setToolRefs(new ArrayList<>());
        }
        if (descriptor.getMeta() == null) {
            descriptor.setMeta(new LinkedHashMap<>());
        }
    }

    public static List<String> copyList(List<String> values) {
        if (values == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(values);
    }

    public static Map<String, String> copyMap(Map<String, String> values) {
        if (values == null) {
            return new LinkedHashMap<>();
        }
        return new LinkedHashMap<>(values);
    }
}
