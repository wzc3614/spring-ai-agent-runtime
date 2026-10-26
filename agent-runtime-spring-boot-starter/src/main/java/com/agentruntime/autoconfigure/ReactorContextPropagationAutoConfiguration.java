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

import com.agentruntime.capability.skill.trace.SkillExecutionContext;
import com.agentruntime.core.springai.MemoryBudgetContextHolder;
import com.agentruntime.core.springai.SpanContextHolder;
import com.agentruntime.core.springai.TurnContextHolder;
import com.agentruntime.observability.reasoning.ReasoningCapture;

import io.micrometer.context.ContextRegistry;
import jakarta.annotation.PostConstruct;
import reactor.core.publisher.Hooks;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;

/**
 * 启用 Reactor 自动上下文传播，并把 {@link SpanContextHolder} 的 parentSpanId / sessionId
 * 注册为可透传的 ThreadLocal。这样流式调用下，编排器经 {@code contextWrite} 写入的 span 上下文
 * 会随 Reactor Context 在各 reactor 操作线程上自动恢复，使工具装饰器在流式路径也能正确建 span。
 *
 * <p>{@code Hooks.enableAutomaticContextPropagation()} 是 JVM 级开关，全局只调用一次。
 */
@AutoConfiguration
@ConditionalOnClass({Hooks.class, ContextRegistry.class})
public class ReactorContextPropagationAutoConfiguration {

    @PostConstruct
    void enableContextPropagation() {
        SpanContextHolder.registerThreadLocalAccessors(ContextRegistry.getInstance());
        MemoryBudgetContextHolder.registerThreadLocalAccessor(ContextRegistry.getInstance());
        TurnContextHolder.registerThreadLocalAccessor(ContextRegistry.getInstance());
        SkillExecutionContext.registerThreadLocalAccessor(ContextRegistry.getInstance());
        ReasoningCapture.registerThreadLocalAccessor(ContextRegistry.getInstance());
        Hooks.enableAutomaticContextPropagation();
    }
}
