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
package com.agentruntime.capability.skill.load;

import com.agentruntime.api.capability.SkillInstructionLoader;
import com.agentruntime.capability.persistence.jdbc.SkillMetadataRepository;
import com.agentruntime.capability.skill.SkillConstants;
import com.agentruntime.capability.skill.registry.ClasspathSkillSource;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按地址前缀加载指令正文。每次调用都重新读取，不缓存。
 */
public class SchemeSkillInstructionLoader implements SkillInstructionLoader {

    private static final Logger logger = LoggerFactory.getLogger(SchemeSkillInstructionLoader.class);

    private final SkillMetadataRepository repository;

    public SchemeSkillInstructionLoader(SkillMetadataRepository repository) {
        this.repository = repository;
    }

    @Override
    public String load(String instructionRef) {
        if (StringUtils.isBlank(instructionRef)) {
            return "";
        }
        try {
            return loadUnchecked(instructionRef);
        } catch (RuntimeException ex) {
            logger.error("skill instruction load failed, ref={}", instructionRef, ex);
            return "";
        }
    }

    private String loadUnchecked(String instructionRef) {
        if (instructionRef.startsWith(SkillConstants.SCHEME_CLASSPATH)) {
            String path = instructionRef.substring(SkillConstants.SCHEME_CLASSPATH.length());
            String text = ClasspathSkillSource.readClasspath(path);
            if (StringUtils.isEmpty(text)) {
                logger.warn("skill instruction not found, ref={}", instructionRef);
            }
            return text;
        }
        if (instructionRef.startsWith(SkillConstants.SCHEME_DB)) {
            if (repository == null) {
                logger.warn("skill db instruction loader missing, ref={}", instructionRef);
                return "";
            }
            String skillId = instructionRef.substring(SkillConstants.SCHEME_DB.length());
            return repository.loadInstruction(skillId);
        }
        logger.warn("unknown skill instruction scheme, ref={}", instructionRef);
        return "";
    }
}
