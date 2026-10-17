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
package com.agentruntime.core.tool;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;

/**
 * 工具对象解析器：把 @Tool 注解的 bean 数组解析为 ToolCallback[]。
 * 薄封装 Spring AI {@link ToolCallbacks#from}，便于测试与扩展。
 */
public class ToolObjectResolver {

    /**
     * 解析 @Tool bean 为 ToolCallback 数组。
     *
     * @param beans @Tool 注解的 bean
     * @return ToolCallback 数组
     */
    public ToolCallback[] resolve(Object... beans) {
        if (beans == null || beans.length == 0) {
            return new ToolCallback[0];
        }
        return ToolCallbacks.from(beans);
    }
}
