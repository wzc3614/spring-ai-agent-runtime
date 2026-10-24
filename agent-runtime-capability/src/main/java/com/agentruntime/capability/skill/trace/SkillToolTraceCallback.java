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
package com.agentruntime.capability.skill.trace;

import com.agentruntime.core.springai.BaseToolCallbackDecorator;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;

import java.util.function.Supplier;

/**
 * 记录一次逻辑工具调用。包在治理装饰器之外、观测装饰器之内，重试不会被拆成多条轨迹。
 * 没有激活上下文时直接透传。
 */
public class SkillToolTraceCallback extends BaseToolCallbackDecorator {

    public SkillToolTraceCallback(ToolCallback delegate) {
        super(delegate);
    }

    @Override
    public String call(String toolInput) {
        return trace(() -> delegate().call(toolInput));
    }

    @Override
    public String call(String toolInput, ToolContext context) {
        return trace(() -> delegate().call(toolInput, context));
    }

    private String trace(Supplier<String> action) {
        long started = System.nanoTime();
        try {
            String output = action.get();
            SkillExecutionContext.record(toolName(), true, SkillExecutionContext.elapsedMillis(started));
            return output;
        } catch (RuntimeException ex) {
            SkillExecutionContext.record(toolName(), false, SkillExecutionContext.elapsedMillis(started));
            throw ex;
        }
    }

    private String toolName() {
        return delegate().getToolDefinition().name();
    }
}
