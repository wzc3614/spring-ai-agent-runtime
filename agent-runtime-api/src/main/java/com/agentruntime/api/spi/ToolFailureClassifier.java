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

import com.agentruntime.api.domain.ToolCall;
import com.agentruntime.api.enums.FailureCategoryEnum;

/**
 * 工具失败分类 SPI。决定 Harness 是否自动重试：
 * TRANSIENT+幂等才重试，AMBIGUOUS 绝不自动重试。默认实现在 core 模块。
 */
public interface ToolFailureClassifier {

    /**
     * 对一次工具执行异常进行分类。
     *
     * @param error    抛出的异常
     * @param toolCall  触发该异常的工具调用（含 idempotent 等元数据）
     * @return 失败分类
     */
    FailureCategoryEnum classify(Throwable error, ToolCall toolCall);
}
