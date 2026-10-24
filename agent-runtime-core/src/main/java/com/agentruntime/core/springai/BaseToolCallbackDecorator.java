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
package com.agentruntime.core.springai;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * ToolCallback 装饰器基类。委托底层 ToolCallback，子类可重写 call() 做结果加工或前置校验。
 * 供 GovernedToolCallback 复用。
 */
public abstract class BaseToolCallbackDecorator implements ToolCallback {

    private final ToolCallback delegate;

    protected BaseToolCallbackDecorator(ToolCallback delegate) {
        this.delegate = delegate;
    }

    /** 被装饰的原生 ToolCallback */
    protected ToolCallback delegate() {
        return delegate;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return delegate.call(toolInput);
    }

    @Override
    public String call(String toolInput, ToolContext context) {
        return delegate.call(toolInput, context);
    }

    // GA 起 ToolCallback 不再暴露 getName/getDescription/getInputTypeSchema，
    // 这些信息经 getToolDefinition().name()/description()/inputSchema() 获取，上方已委托。
}
