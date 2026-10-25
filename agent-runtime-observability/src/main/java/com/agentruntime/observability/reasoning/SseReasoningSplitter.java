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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

/**
 * 把流式响应按行切开，取出每条 data 里的思考增量。行被拆到多个网络包时先拼回去。
 */
public final class SseReasoningSplitter {

    private final ObjectMapper objectMapper;

    private final StringBuilder pending = new StringBuilder();

    public SseReasoningSplitter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 吃进一段原始正文。
     *
     * @param chunk  原始文本
     * @param buffer 思考过程缓冲
     */
    public void accept(String chunk, ReasoningBuffer buffer) {
        if (chunk == null || buffer == null) {
            return;
        }
        pending.append(chunk);
        int lineLength = nextLineLength();
        while (lineLength >= 0) {
            String line = pending.substring(0, lineLength);
            pending.delete(0, lineLength + lineBreakLength(lineLength));
            captureLine(line, buffer);
            lineLength = nextLineLength();
        }
    }

    private void captureLine(String line, ReasoningBuffer buffer) {
        String trimmed = line.trim();
        if (!trimmed.startsWith(ReasoningConstants.SSE_DATA_PREFIX)) {
            return;
        }
        String data = trimmed.substring(ReasoningConstants.SSE_DATA_PREFIX.length()).trim();
        if (!StringUtils.hasText(data) || ReasoningConstants.SSE_DONE.equals(data)) {
            return;
        }
        buffer.append(ReasoningContentReader.fromJson(data, objectMapper));
    }

    private int nextLineLength() {
        for (int i = 0; i < pending.length(); i++) {
            char current = pending.charAt(i);
            if (current == '\n') {
                return i;
            }
            if (current == '\r' && i + 1 < pending.length()) {
                return i;
            }
        }
        return -1;
    }

    private int lineBreakLength(int lineLength) {
        if (pending.charAt(lineLength) == '\r' && lineLength + 1 < pending.length()
                && pending.charAt(lineLength + 1) == '\n') {
            return 2;
        }
        return 1;
    }
}
