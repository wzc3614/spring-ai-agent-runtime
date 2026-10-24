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
import com.agentruntime.api.capability.SkillRegistry;
import com.agentruntime.api.capability.SkillSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多层 Skill 注册表。来源按构造顺序覆盖（后者胜出）。
 * 元数据按 revision 懒加载；工具悬空校验发生在 {@link #list(Set)}，因此不同 Agent 不会互相污染快照。
 * {@code enabled=false} 的合法记录仍占据 skillId，用来下线同名 Skill，不要求工具仍存在。
 */
public class CompositeSkillRegistry implements SkillRegistry {

    private static final Logger logger = LoggerFactory.getLogger(CompositeSkillRegistry.class);

    private static final String INITIAL_REVISION = "";

    private final List<SkillSource> sources;

    private final SkillDescriptorValidator validator;

    private final Object reloadLock = new Object();

    private volatile Material material = Material.empty();

    /** 同一 revision 下悬空引用只打一次日志 */
    private final Set<String> loggedDangling = ConcurrentHashMap.newKeySet();

    public CompositeSkillRegistry(List<SkillSource> sources, SkillDescriptorValidator validator) {
        this.sources = sources == null ? List.of() : List.copyOf(sources);
        this.validator = validator;
    }

    @Override
    public List<SkillDescriptor> list(Set<String> toolNames) {
        reloadIfChanged();
        return merge(toolNames);
    }

    @Override
    public String revision() {
        return material.revision;
    }

    private void reloadIfChanged() {
        String revision;
        try {
            revision = computeRevision();
        } catch (RuntimeException ex) {
            logger.error("skill registry revision check failed", ex);
            return;
        }
        if (revision.equals(material.revision)) {
            return;
        }
        synchronized (reloadLock) {
            try {
                revision = computeRevision();
            } catch (RuntimeException ex) {
                logger.error("skill registry revision check failed", ex);
                return;
            }
            if (revision.equals(material.revision)) {
                return;
            }
            List<Layer> layers = new ArrayList<>();
            for (SkillSource source : sources) {
                layers.add(loadLayer(source));
            }
            material = new Material(revision, layers);
            loggedDangling.clear();
        }
    }

    private Layer loadLayer(SkillSource source) {
        Map<String, SkillDescriptor> descriptors = new LinkedHashMap<>();
        List<SkillDescriptor> loaded;
        try {
            loaded = source.loadMetadata();
        } catch (RuntimeException ex) {
            logger.error("skill source load failed, source={}", source.name(), ex);
            return new Layer(source.name(), descriptors);
        }
        if (CollectionUtils.isEmpty(loaded)) {
            return new Layer(source.name(), descriptors);
        }
        for (SkillDescriptor descriptor : loaded) {
            if (!validator.isWellFormed(descriptor)) {
                continue;
            }
            descriptors.put(descriptor.getSkillId(), descriptor);
        }
        return new Layer(source.name(), descriptors);
    }

    private List<SkillDescriptor> merge(Set<String> toolNames) {
        Material current = material;
        Map<String, SkillDescriptor> merged = new LinkedHashMap<>();
        for (Layer layer : current.layers) {
            for (SkillDescriptor candidate : layer.descriptors.values()) {
                applyCandidate(merged, layer.name, candidate, toolNames, current.revision);
            }
        }
        List<SkillDescriptor> visible = new ArrayList<>();
        for (SkillDescriptor descriptor : merged.values()) {
            if (descriptor.isEnabled()) {
                visible.add(descriptor);
            }
        }
        return List.copyOf(visible);
    }

    private void applyCandidate(Map<String, SkillDescriptor> merged, String sourceName,
                                SkillDescriptor candidate, Set<String> toolNames, String revision) {
        if (!candidate.isEnabled()) {
            merged.put(candidate.getSkillId(), candidate);
            return;
        }
        if (validator.hasResolvableTools(candidate, toolNames)) {
            merged.put(candidate.getSkillId(), candidate);
            return;
        }
        String token = revision + ":" + sourceName + ":" + candidate.getSkillId();
        if (!loggedDangling.add(token)) {
            return;
        }
        logger.warn("reject skill: dangling tool ref, skillId={}, source={}, toolRefs={}",
                candidate.getSkillId(), sourceName, candidate.getToolRefs());
        if (merged.containsKey(candidate.getSkillId())) {
            logger.info("keep previous skill after rejected override, skillId={}, source={}",
                    candidate.getSkillId(), sourceName);
        }
    }

    private String computeRevision() {
        if (CollectionUtils.isEmpty(sources)) {
            return INITIAL_REVISION;
        }
        StringBuilder builder = new StringBuilder();
        for (SkillSource source : sources) {
            String revision = source.revision();
            builder.append(source.name()).append('=').append(revision == null ? "" : revision).append('|');
        }
        return builder.toString();
    }

    private static final class Material {

        private final String revision;

        private final List<Layer> layers;

        private Material(String revision, List<Layer> layers) {
            this.revision = revision;
            this.layers = layers;
        }

        private static Material empty() {
            return new Material(INITIAL_REVISION, List.of());
        }
    }

    private static final class Layer {

        private final String name;

        private final Map<String, SkillDescriptor> descriptors;

        private Layer(String name, Map<String, SkillDescriptor> descriptors) {
            this.name = name;
            this.descriptors = descriptors;
        }
    }
}
