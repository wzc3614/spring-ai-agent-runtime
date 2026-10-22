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

import com.agentruntime.core.concurrency.parallel.ParallelToolCallingManager;
import com.agentruntime.core.concurrency.parallel.ParallelToolExecutor;
import com.agentruntime.core.tool.DefaultToolFailureClassifier;
import com.agentruntime.core.tool.DefaultToolMetaRegistry;
import com.agentruntime.core.tool.ToolGovernanceDefaults;
import com.agentruntime.core.tool.ToolMetaRegistry;
import com.agentruntime.api.spi.ToolFailureClassifier;

import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 注册并行 {@link ToolCallingManager}，替换 Spring AI 默认的 {@code DefaultToolCallingManager}（顺序执行）。
 * 加载顺序排在 Spring AI 的 {@code ToolCallingAutoConfiguration} 之前，使后者 {@code @ConditionalOnMissingBean}
 * 自动退让。使用者可自定义 {@link ToolCallingManager} bean 覆盖（例如退回串行）。
 *
 * <p>协作者（{@link ToolCallbackResolver}、{@link ToolExecutionExceptionProcessor}、
 * {@link ObservationRegistry}）在 Spring AI 自动配置存在时复用；缺失时经 builder 应用与 Spring AI
 * 相同的默认值。同时装配工具治理三件套：{@link ToolMetaRegistry}、{@link ToolFailureClassifier}、
 * {@link ToolGovernanceDefaults}（来自 {@link ToolGovernanceProperties}），供重试/超时使用。
 */
@AutoConfiguration
@AutoConfigureBefore(name = "org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration")
@ConditionalOnClass({ToolCallingManager.class, ParallelToolExecutor.class})
@EnableConfigurationProperties(ToolGovernanceProperties.class)
public class ParallelToolCallingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ToolMetaRegistry.class)
    public ToolMetaRegistry toolMetaRegistry() {
        return new DefaultToolMetaRegistry();
    }

    @Bean
    @ConditionalOnMissingBean(ToolFailureClassifier.class)
    public ToolFailureClassifier toolFailureClassifier() {
        return new DefaultToolFailureClassifier();
    }

    @Bean
    @ConditionalOnMissingBean(ToolGovernanceDefaults.class)
    public ToolGovernanceDefaults toolGovernanceDefaults(ToolGovernanceProperties properties) {
        return properties.toDefaults();
    }

    @Bean
    @ConditionalOnMissingBean(ToolCallingManager.class)
    public ToolCallingManager parallelToolCallingManager(
            ObjectProvider<ToolCallbackResolver> toolCallbackResolverProvider,
            ObjectProvider<ToolExecutionExceptionProcessor> toolExecutionExceptionProcessorProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ToolMetaRegistry toolMetaRegistry, ToolGovernanceDefaults governanceDefaults) {
        ParallelToolCallingManager.Builder builder = ParallelToolCallingManager.builder();
        builder.toolMetaRegistry(toolMetaRegistry).governanceDefaults(governanceDefaults);
        ToolCallbackResolver resolver = toolCallbackResolverProvider.getIfAvailable();
        if (resolver != null) {
            builder.toolCallbackResolver(resolver);
        }
        ToolExecutionExceptionProcessor processor = toolExecutionExceptionProcessorProvider.getIfAvailable();
        if (processor != null) {
            builder.toolExecutionExceptionProcessor(processor);
        }
        ObservationRegistry registry = observationRegistryProvider.getIfAvailable();
        if (registry != null) {
            builder.observationRegistry(registry);
        }
        return builder.build();
    }
}
