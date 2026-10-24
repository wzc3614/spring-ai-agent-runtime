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
package com.agentruntime.capability.persistence.jdbc;

import com.agentruntime.api.capability.SkillDescriptor;

import java.util.List;

/**
 * Skill 元数据与指令正文的读取端口。revision 查询不读取 instruction 列。
 */
public interface SkillMetadataRepository {

    /**
     * 读取 revision 行。
     *
     * @return 按 skillId 排序的 revision
     */
    List<SkillRevision> listRevisions();

    /**
     * 读取元数据。instructionRef 形如 {@code db:{skillId}}，不含正文。
     *
     * @return 描述符列表
     */
    List<SkillDescriptor> listMetadata();

    /**
     * 读取指令正文。
     *
     * @param skillId Skill 主键
     * @return 正文，不存在时返回空串
     */
    String loadInstruction(String skillId);
}
