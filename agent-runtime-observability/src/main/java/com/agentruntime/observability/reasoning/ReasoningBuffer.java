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
 * 一次模型调用的思考过程缓冲。同步拦截器和流式过滤器往同一个实例上追加。
 */
public final class ReasoningBuffer {

    private final StringBuilder text = new StringBuilder();

    /**
     * 追加一段思考过程。空白片段忽略。
     *
     * @param chunk 片段
     */
    public void append(String chunk) {
        if (!StringUtils.hasText(chunk)) {
            return;
        }
        synchronized (text) {
            text.append(chunk);
        }
    }

    /**
     * @return 目前拼好的思考过程。没有内容时为空字符串
     */
    public String text() {
        synchronized (text) {
            return text.toString();
        }
    }
}
