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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 思考过程的提取规则。
 */
class ReasoningContentReaderTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void readsReasoningFromMessageAndDelta() {
        String completion = """
                {"choices":[{"message":{"content":"","reasoning_content":"先查用户"}}]}
                """;
        String chunk = """
                {"choices":[{"delta":{"reasoning_content":"再看"}}]}
                """;

        assertThat(ReasoningContentReader.fromJson(completion, objectMapper)).isEqualTo("先查用户");
        assertThat(ReasoningContentReader.fromJson(chunk, objectMapper)).isEqualTo("再看");
    }

    @Test
    void ignoresMissingOrBrokenPayloads() {
        assertThat(ReasoningContentReader.fromJson("{\"choices\":[{\"message\":{\"content\":\"答案\"}}]}", objectMapper))
                .isNull();
        assertThat(ReasoningContentReader.fromJson("not-json", objectMapper)).isNull();
        assertThat(ReasoningContentReader.fromJson("{\"choices\":[]}", objectMapper)).isNull();
    }

    @Test
    void selectsReasoningBeforeToolPreambleAndSkipsPlainAnswers() {
        assertThat(ThinkingSelector.select("思考", true, "旁白")).isEqualTo("思考");
        assertThat(ThinkingSelector.select(null, true, "旁白")).isEqualTo("旁白");
        assertThat(ThinkingSelector.select("  ", false, "最终回答")).isNull();
        assertThat(ThinkingSelector.select(null, false, "最终回答")).isNull();
    }

    @Test
    void stitchesReasoningSplitAcrossSseChunks() {
        SseReasoningSplitter splitter = new SseReasoningSplitter(objectMapper);
        ReasoningBuffer buffer = new ReasoningBuffer();
        splitter.accept("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"先", buffer);
        splitter.accept("查\"}}]}\n", buffer);
        splitter.accept("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"用户\"}}]}\r\n", buffer);
        splitter.accept("data: [DONE]\n", buffer);
        splitter.accept("data: not-json\n", buffer);

        assertThat(buffer.text()).isEqualTo("先查用户");
    }
}
