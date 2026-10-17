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

import com.agentruntime.api.domain.ToolCall;
import com.agentruntime.api.enums.FailureCategoryEnum;
import com.agentruntime.api.spi.ToolFailureClassifier;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;

/**
 * 工具失败分类默认实现。
 * <ul>
 *   <li>非幂等 + 超时类 → AMBIGUOUS（绝不自动重试）</li>
 *   <li>超时/连接/IO 类 → TRANSIENT（幂等可重试）</li>
 *   <li>其余 → TERMINAL（不重试，回填模型）</li>
 * </ul>
 */
public class DefaultToolFailureClassifier implements ToolFailureClassifier {

    @Override
    public FailureCategoryEnum classify(Throwable error, ToolCall toolCall) {
        if (error == null) {
            return FailureCategoryEnum.TERMINAL;
        }
        boolean timeoutLike = isTimeoutLike(error);
        boolean idempotent = toolCall != null && toolCall.isIdempotent();
        if (!idempotent && timeoutLike) {
            return FailureCategoryEnum.AMBIGUOUS;
        }
        if (timeoutLike || error instanceof IOException) {
            return FailureCategoryEnum.TRANSIENT;
        }
        return FailureCategoryEnum.TERMINAL;
    }

    private boolean isTimeoutLike(Throwable error) {
        return error instanceof SocketTimeoutException || error instanceof ConnectException
                || error.getClass().getName().contains("Timeout");
    }
}
