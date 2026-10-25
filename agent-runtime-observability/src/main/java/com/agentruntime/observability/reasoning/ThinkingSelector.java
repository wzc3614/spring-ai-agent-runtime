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
package com.agentruntime.observability.reasoning;

import org.springframework.util.StringUtils;

/**
 * 决定一次模型调用要不要记思考过程。有 {@code reasoning_content} 时用它。
 * 没有、但这次回复同时带了工具调用时，用模型写在工具旁边的正文。
 * 纯最终回答不记成思考过程，避免和 {@code FINAL_REPLY} 重复。
 */
public final class ThinkingSelector {

    private ThinkingSelector() {
    }

    /**
     * 选择要落库的思考过程。
     *
     * @param reasoningContent 网关返回的思考过程
     * @param hasToolCalls     这次回复是否包含工具调用
     * @param assistantText    模型正文
     * @return 思考过程。不该记时为空
     */
    public static String select(String reasoningContent, boolean hasToolCalls, String assistantText) {
        if (StringUtils.hasText(reasoningContent)) {
            return reasoningContent;
        }
        if (hasToolCalls && StringUtils.hasText(assistantText)) {
            return assistantText;
        }
        return null;
    }
}
