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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.TreeMap;

/**
 * 对 Skill 元数据做 SHA-256。不含指令正文，也不含 contentHash 自身。
 */
public class SkillMetadataHasher {

    private final ObjectMapper objectMapper;

    public SkillMetadataHasher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 计算描述符的元数据哈希。
     *
     * @param descriptor 已规范化的描述符
     * @return 十六进制哈希
     */
    public String hash(SkillDescriptor descriptor) {
        TreeMap<String, Object> canonical = new TreeMap<>();
        canonical.put("skillId", descriptor.getSkillId());
        canonical.put("name", descriptor.getName());
        canonical.put("description", descriptor.getDescription());
        canonical.put("notFor", SkillDescriptorNormalizer.copyList(descriptor.getNotFor()));
        canonical.put("triggers", SkillDescriptorNormalizer.copyList(descriptor.getTriggers()));
        canonical.put("toolRefs", SkillDescriptorNormalizer.copyList(descriptor.getToolRefs()));
        canonical.put("instructionRef", descriptor.getInstructionRef());
        canonical.put("version", descriptor.getVersion());
        canonical.put("enabled", descriptor.isEnabled());
        canonical.put("meta", new TreeMap<>(SkillDescriptorNormalizer.copyMap(descriptor.getMeta())));
        try {
            byte[] json = objectMapper.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(digest(json));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("skill metadata hash failed", ex);
        }
    }

    private byte[] digest(byte[] json) {
        try {
            return MessageDigest.getInstance(SkillConstants.HASH_ALGORITHM).digest(json);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("skill metadata hash failed", ex);
        }
    }
}
