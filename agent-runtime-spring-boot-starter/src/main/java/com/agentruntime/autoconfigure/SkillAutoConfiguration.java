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
package com.agentruntime.autoconfigure;

import com.agentruntime.api.capability.SkillInstructionLoader;
import com.agentruntime.api.capability.SkillRegistry;
import com.agentruntime.api.capability.SkillRouter;
import com.agentruntime.api.capability.SkillSource;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.capability.persistence.jdbc.JdbcSkillRepository;
import com.agentruntime.capability.persistence.jdbc.SkillMetadataRepository;
import com.agentruntime.capability.skill.DefaultSkillRuntime;
import com.agentruntime.capability.skill.SkillConstants;
import com.agentruntime.capability.skill.SkillRuntime;
import com.agentruntime.capability.skill.load.SchemeSkillInstructionLoader;
import com.agentruntime.capability.skill.load.SkillInstructionBudgeter;
import com.agentruntime.capability.skill.observe.SkillTurnObserver;
import com.agentruntime.capability.skill.prompt.SkillCatalogFormatter;
import com.agentruntime.capability.skill.prompt.SkillPromptComposer;
import com.agentruntime.capability.skill.registry.ClasspathSkillSource;
import com.agentruntime.capability.skill.registry.CompositeSkillRegistry;
import com.agentruntime.capability.skill.registry.JdbcSkillSource;
import com.agentruntime.capability.skill.registry.SkillDescriptorValidator;
import com.agentruntime.capability.skill.registry.SkillMetadataHasher;
import com.agentruntime.capability.skill.route.CascadingSkillRouter;
import com.agentruntime.capability.skill.route.KeywordSkillMatcher;
import com.agentruntime.capability.skill.route.LlmSkillRouter;
import com.agentruntime.capability.skill.select.SkillToolSelector;
import com.agentruntime.observability.ObservingChatModel;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Skill 管道自动装配。classpath 先于 JDBC，同名时数据库覆盖。
 * 未配置 {@code skillRouterChatModel} 时只保留关键词路由。
 */
@AutoConfiguration
@EnableConfigurationProperties(SkillProperties.class)
@ConditionalOnClass(SkillRuntime.class)
@ConditionalOnProperty(prefix = "agent.runtime.skill", name = "enabled", matchIfMissing = true)
public class SkillAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SkillDescriptorValidator skillDescriptorValidator() {
        return new SkillDescriptorValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillMetadataHasher skillMetadataHasher(ObjectMapper objectMapper) {
        return new SkillMetadataHasher(objectMapper);
    }

    @Bean
    @Order(0)
    @ConditionalOnProperty(prefix = "agent.runtime.skill.sources.classpath", name = "enabled", matchIfMissing = true)
    public SkillSource classpathSkillSource(SkillProperties properties, SkillMetadataHasher hasher,
                                            ObjectMapper objectMapper) {
        return new ClasspathSkillSource(properties.getSources().getClasspath().getLocation(), hasher, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.runtime.skill.sources.jdbc", name = "enabled", matchIfMissing = true)
    @ConditionalOnBean(JdbcTemplate.class)
    public SkillMetadataRepository skillMetadataRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                                           SkillMetadataHasher hasher) {
        return new JdbcSkillRepository(jdbcTemplate, objectMapper, hasher);
    }

    @Bean
    @Order(100)
    @ConditionalOnBean(SkillMetadataRepository.class)
    public SkillSource jdbcSkillSource(SkillMetadataRepository repository) {
        return new JdbcSkillSource(repository);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillRegistry skillRegistry(ObjectProvider<SkillSource> sources, SkillDescriptorValidator validator) {
        return new CompositeSkillRegistry(sources.orderedStream().toList(), validator);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillInstructionLoader skillInstructionLoader(ObjectProvider<SkillMetadataRepository> repository) {
        return new SchemeSkillInstructionLoader(repository.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillRouter skillRouter(SkillProperties properties, ObjectMapper objectMapper,
                                   ObservabilityRecorder recorder,
                                   @Qualifier(SkillProperties.ROUTER_MODEL_BEAN)
                                   ObjectProvider<ChatModel> routerModel) {
        int topN = properties.getRouter().getTopN();
        if (topN < 1) {
            topN = SkillConstants.DEFAULT_TOP_N;
        }
        LlmSkillRouter llmRouter = null;
        ChatModel model = routerModel.getIfAvailable();
        if (properties.getRouter().isLlmEnabled() && model != null) {
            // 路由模型只建 MODEL_CALL，不采集思考过程。三参构造默认关闭采集。
            ChatModel observed = model instanceof ObservingChatModel
                    ? model : new ObservingChatModel(model, recorder, objectMapper);
            llmRouter = new LlmSkillRouter(observed, objectMapper, topN);
        }
        return new CascadingSkillRouter(new KeywordSkillMatcher(), llmRouter, topN);
    }

    @Bean
    @ConditionalOnMissingBean
    public SkillRuntime skillRuntime(SkillRegistry registry, SkillRouter router,
                                     SkillInstructionLoader instructionLoader, TokenEstimator tokenEstimator,
                                     ObservabilityRecorder recorder, ObjectMapper objectMapper,
                                     SkillProperties properties) {
        int topN = properties.getRouter().getTopN();
        if (topN < 1) {
            topN = SkillConstants.DEFAULT_TOP_N;
        }
        return new DefaultSkillRuntime(registry, router, instructionLoader,
                new SkillInstructionBudgeter(tokenEstimator), new SkillCatalogFormatter(),
                new SkillPromptComposer(), new SkillToolSelector(),
                new SkillTurnObserver(recorder, objectMapper), tokenEstimator,
                properties.getInstructionMaxTokens(), topN);
    }
}
