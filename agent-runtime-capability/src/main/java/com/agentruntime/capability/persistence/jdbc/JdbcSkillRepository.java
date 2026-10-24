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
import com.agentruntime.capability.skill.SkillConstants;
import com.agentruntime.capability.skill.registry.SkillDescriptorNormalizer;
import com.agentruntime.capability.skill.registry.SkillMetadataHasher;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * agent_skill 的 JDBC 读取实现。元数据查询不选 instruction 列。
 */
public class JdbcSkillRepository implements SkillMetadataRepository {

    private static final Logger logger = LoggerFactory.getLogger(JdbcSkillRepository.class);

    private static final String SQL_REVISIONS =
            "SELECT skill_id, content_hash, updated_at FROM agent_skill ORDER BY skill_id";

    private static final String SQL_METADATA =
            "SELECT skill_id, name, description, not_for, triggers, tool_refs, version, "
                    + "content_hash, enabled, meta FROM agent_skill ORDER BY skill_id";

    private static final String SQL_INSTRUCTION =
            "SELECT instruction FROM agent_skill WHERE skill_id = ?";

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    private final SkillMetadataHasher hasher;

    public JdbcSkillRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper, SkillMetadataHasher hasher) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.hasher = hasher;
    }

    @Override
    public List<SkillRevision> listRevisions() {
        return jdbcTemplate.query(SQL_REVISIONS, (rs, rowNum) -> mapRevision(rs));
    }

    @Override
    public List<SkillDescriptor> listMetadata() {
        return jdbcTemplate.query(SQL_METADATA, (rs, rowNum) -> mapDescriptor(rs));
    }

    @Override
    public String loadInstruction(String skillId) {
        List<String> rows = jdbcTemplate.query(SQL_INSTRUCTION,
                (rs, rowNum) -> rs.getString("instruction"), skillId);
        if (rows.isEmpty() || rows.get(0) == null) {
            return "";
        }
        return rows.get(0);
    }

    private SkillRevision mapRevision(ResultSet rs) throws SQLException {
        SkillRevision revision = new SkillRevision();
        revision.setSkillId(rs.getString("skill_id"));
        revision.setContentHash(rs.getString("content_hash"));
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        revision.setUpdatedAtEpochMilli(updatedAt == null ? 0L : updatedAt.getTime());
        return revision;
    }

    private SkillDescriptor mapDescriptor(ResultSet rs) throws SQLException {
        SkillDescriptor descriptor = new SkillDescriptor();
        descriptor.setSkillId(rs.getString("skill_id"));
        descriptor.setName(rs.getString("name"));
        descriptor.setDescription(rs.getString("description"));
        descriptor.setNotFor(readStringList(rs.getString("not_for")));
        descriptor.setTriggers(readStringList(rs.getString("triggers")));
        descriptor.setToolRefs(readStringList(rs.getString("tool_refs")));
        descriptor.setInstructionRef(SkillConstants.SCHEME_DB + descriptor.getSkillId());
        descriptor.setVersion(rs.getString("version"));
        descriptor.setEnabled(rs.getBoolean("enabled"));
        descriptor.setMeta(readMeta(rs.getString("meta")));
        SkillDescriptorNormalizer.normalize(descriptor);
        String storedHash = rs.getString("content_hash");
        String computed = hasher.hash(descriptor);
        if (!computed.equals(storedHash)) {
            logger.warn("skill content hash mismatch, skillId={}", descriptor.getSkillId());
        }
        descriptor.setContentHash(computed);
        return descriptor;
    }

    private List<String> readStringList(String json) {
        if (StringUtils.isBlank(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readerForListOf(String.class).readValue(json);
        } catch (IOException ex) {
            logger.warn("skill json list unreadable");
            return new ArrayList<>();
        }
    }

    private Map<String, String> readMeta(String json) {
        Map<String, String> meta = new LinkedHashMap<>();
        if (StringUtils.isBlank(json)) {
            return meta;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node != null && node.isObject()) {
                node.fields().forEachRemaining(entry -> meta.put(entry.getKey(), entry.getValue().asText()));
            }
        } catch (JsonProcessingException ex) {
            logger.warn("skill meta unreadable");
        }
        return meta;
    }
}
