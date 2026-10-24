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
import com.agentruntime.api.capability.SkillSource;
import com.agentruntime.capability.persistence.jdbc.SkillMetadataRepository;
import com.agentruntime.capability.persistence.jdbc.SkillRevision;
import com.agentruntime.capability.skill.SkillConstants;

import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 从 agent_skill 加载元数据。revision 由 skillId、content_hash、updated_at 组成，不读取指令正文。
 */
public class JdbcSkillSource implements SkillSource {

    private final SkillMetadataRepository repository;

    public JdbcSkillSource(SkillMetadataRepository repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return SkillConstants.SOURCE_JDBC;
    }

    @Override
    public String revision() {
        List<SkillRevision> rows = repository.listRevisions();
        if (CollectionUtils.isEmpty(rows)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (SkillRevision row : rows) {
            builder.append(row.getSkillId()).append('=').append(row.getContentHash())
                    .append('@').append(row.getUpdatedAtEpochMilli()).append(';');
        }
        return builder.toString();
    }

    @Override
    public List<SkillDescriptor> loadMetadata() {
        return repository.listMetadata();
    }
}
