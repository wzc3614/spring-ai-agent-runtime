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
package com.agentruntime.api.spi;

import com.agentruntime.api.memory.ToolCompactionInput;

/**
 * 自定义工具结果渲染器。只生成注入上下文的文本，不写数据库。
 * 实现注册为 Spring bean 后，用 {@link #code()} 挂到工具规则上。
 */
public interface ToolResultCompactor {

    /**
     * 策略编码，与 {@code ToolCompactionRule.custom} 的 code 对应。
     *
     * @return 非空编码
     */
    String code();

    /**
     * 渲染注入模型的文本。返回 null 时调用方保留原文。
     *
     * @param input 单条工具结果
     * @return 上下文视图
     */
    String renderForContext(ToolCompactionInput input);
}
