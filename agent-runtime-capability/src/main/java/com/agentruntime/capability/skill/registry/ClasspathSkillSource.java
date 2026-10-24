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
import com.agentruntime.capability.skill.SkillConstants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 classpath 的 skill.json 加载 Skill 元数据。jar 内资源没有可靠的 lastModified 时，
 * revision 在进程内保持稳定；可写目录则用最后修改时间发现变更。
 */
public class ClasspathSkillSource implements SkillSource {

    private static final Logger logger = LoggerFactory.getLogger(ClasspathSkillSource.class);

    private static final String INSTRUCTION_SUFFIX = "/INSTRUCTION.md";

    private static final String SKILLS_DIR = "skills/";

    private final String location;

    private final SkillMetadataHasher hasher;

    private final ObjectMapper objectMapper;

    private final ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();

    /** jar 内资源 revision 只算一次 */
    private volatile String sealedRevision;

    public ClasspathSkillSource(String location, SkillMetadataHasher hasher, ObjectMapper objectMapper) {
        this.location = location;
        this.hasher = hasher;
        this.objectMapper = objectMapper;
    }

    @Override
    public String name() {
        return SkillConstants.SOURCE_CLASSPATH;
    }

    @Override
    public String revision() {
        if (sealedRevision != null) {
            return sealedRevision;
        }
        Resource[] resources = resolve();
        boolean sealed = true;
        StringBuilder builder = new StringBuilder();
        for (Resource resource : resources) {
            long modified = lastModified(resource);
            builder.append(resource.getDescription()).append('#');
            if (modified > 0) {
                sealed = false;
                builder.append(modified);
            } else {
                builder.append('0');
            }
            builder.append(';');
        }
        String revision = builder.toString();
        if (sealed) {
            sealedRevision = revision;
        }
        return revision;
    }

    @Override
    public List<SkillDescriptor> loadMetadata() {
        List<SkillDescriptor> descriptors = new ArrayList<>();
        for (Resource resource : resolve()) {
            SkillDescriptor descriptor = read(resource);
            if (descriptor != null) {
                descriptors.add(descriptor);
            }
        }
        descriptors.sort(Comparator.comparing(SkillDescriptor::getSkillId, Comparator.nullsLast(String::compareTo)));
        return descriptors;
    }

    private SkillDescriptor read(Resource resource) {
        try (InputStream input = resource.getInputStream()) {
            JsonNode node = objectMapper.readTree(input);
            SkillDescriptor descriptor = toDescriptor(node);
            SkillDescriptorNormalizer.normalize(descriptor);
            if (StringUtils.isBlank(descriptor.getInstructionRef()) && StringUtils.isNotBlank(descriptor.getSkillId())) {
                descriptor.setInstructionRef(SkillConstants.SCHEME_CLASSPATH + SKILLS_DIR
                        + descriptor.getSkillId() + INSTRUCTION_SUFFIX);
            }
            descriptor.setContentHash(hasher.hash(descriptor));
            return descriptor;
        } catch (IOException ex) {
            logger.warn("skip unreadable skill resource, resource={}", resource.getDescription());
            return null;
        }
    }

    private SkillDescriptor toDescriptor(JsonNode node) {
        SkillDescriptor descriptor = new SkillDescriptor();
        descriptor.setSkillId(text(node, "skillId"));
        descriptor.setName(text(node, "name"));
        descriptor.setDescription(text(node, "description"));
        descriptor.setNotFor(stringList(node.get("notFor")));
        descriptor.setTriggers(stringList(node.get("triggers")));
        descriptor.setToolRefs(stringList(node.get("toolRefs")));
        descriptor.setInstructionRef(text(node, "instructionRef"));
        descriptor.setVersion(text(node, "version"));
        descriptor.setEnabled(!node.has("enabled") || node.get("enabled").asBoolean(true));
        descriptor.setMeta(meta(node.get("meta")));
        return descriptor;
    }

    private List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return values;
        }
        for (JsonNode item : node) {
            if (item != null && !item.isNull()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private Map<String, String> meta(JsonNode node) {
        Map<String, String> values = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return values;
        }
        node.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        return values;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        return value.asText();
    }

    private Resource[] resolve() {
        try {
            Resource[] resources = resolver.getResources(location);
            return resources == null ? new Resource[0] : resources;
        } catch (IOException ex) {
            logger.warn("skill classpath location unreadable, location={}", location);
            return new Resource[0];
        }
    }

    private long lastModified(Resource resource) {
        try {
            return resource.lastModified();
        } catch (IOException ex) {
            return 0L;
        }
    }

    /**
     * 读取 classpath 指令正文。供加载器复用，不缓存。
     *
     * @param path classpath 内的相对路径，不含 scheme
     * @return 正文；不存在时返回空串
     */
    public static String readClasspath(String path) {
        if (StringUtils.isBlank(path)) {
            return "";
        }
        Resource resource = new PathMatchingResourcePatternResolver().getResource(SkillConstants.SCHEME_CLASSPATH + path);
        if (!resource.exists()) {
            return "";
        }
        try (InputStream input = resource.getInputStream()) {
            return StreamUtils.copyToString(input, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            return "";
        }
    }
}
