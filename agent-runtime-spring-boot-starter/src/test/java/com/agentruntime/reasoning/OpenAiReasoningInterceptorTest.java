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
package com.agentruntime.reasoning;

import com.agentruntime.observability.reasoning.ReasoningCapture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 同步聊天补全把 reasoning_content 放进当前采集缓冲，并把正文交还调用方。
 */
class OpenAiReasoningInterceptorTest {

    @AfterEach
    void clearCapture() {
        ReasoningCapture.clear();
    }

    @Test
    void capturesReasoningAndReplaysTheBody() throws Exception {
        ReasoningCapture.open();
        byte[] json = """
                {"choices":[{"message":{"reasoning_content":"先查用户","content":""}}]}
                """.getBytes(StandardCharsets.UTF_8);
        OpenAiReasoningInterceptor interceptor = new OpenAiReasoningInterceptor(new ObjectMapper());

        ClientHttpResponse response = interceptor.intercept(request("/compatible-mode/v1/chat/completions"), new byte[0],
                (req, body) -> new StubResponse(json));

        assertThat(ReasoningCapture.current().text()).isEqualTo("先查用户");
        assertThat(StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8)).contains("先查用户");
    }

    @Test
    void ignoresOtherPathsAndUnscopedCalls() throws Exception {
        ReasoningCapture.open();
        byte[] json = """
                {"choices":[{"message":{"reasoning_content":"不要记"}}]}
                """.getBytes(StandardCharsets.UTF_8);
        OpenAiReasoningInterceptor interceptor = new OpenAiReasoningInterceptor(new ObjectMapper());

        interceptor.intercept(request("/v1/embeddings"), new byte[0], (req, body) -> new StubResponse(json));
        assertThat(ReasoningCapture.current().text()).isEmpty();

        ReasoningCapture.clear();
        ClientHttpResponse response = interceptor.intercept(request("/v1/chat/completions"), new byte[0],
                (req, body) -> new StubResponse(json));
        assertThat(StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8)).contains("不要记");
        assertThat(ReasoningCapture.current()).isNull();
    }

    private HttpRequest request(String path) {
        HttpRequest request = mock(HttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("https://example.com" + path));
        return request;
    }

    private static final class StubResponse implements ClientHttpResponse {

        private final byte[] payload;

        private StubResponse(byte[] payload) {
            this.payload = payload;
        }

        @Override
        public HttpStatusCode getStatusCode() {
            return HttpStatus.OK;
        }

        @Override
        public String getStatusText() {
            return "OK";
        }

        @Override
        public HttpHeaders getHeaders() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            return headers;
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(payload);
        }

        @Override
        public void close() {
        }
    }
}
