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
import com.agentruntime.observability.reasoning.ReasoningConstants;
import com.agentruntime.observability.reasoning.ReasoningContentReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.util.StreamUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * 从同步聊天补全响应里取出 {@code reasoning_content}。只在模型调用已经打开采集缓冲时工作。
 */
public class OpenAiReasoningInterceptor implements ClientHttpRequestInterceptor {

    private final ObjectMapper objectMapper;

    public OpenAiReasoningInterceptor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ClientHttpResponse intercept(org.springframework.http.HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        if (ReasoningCapture.current() == null || !isChatCompletion(request.getURI())) {
            return response;
        }
        byte[] payload = StreamUtils.copyToByteArray(response.getBody());
        capture(response, payload);
        return new ReplayClientHttpResponse(response, payload);
    }

    private void capture(ClientHttpResponse response, byte[] payload) {
        MediaType contentType = response.getHeaders().getContentType();
        if (contentType != null && !MediaType.APPLICATION_JSON.isCompatibleWith(contentType)) {
            return;
        }
        ReasoningCapture.append(ReasoningContentReader.fromCompletion(payload, objectMapper));
    }

    private boolean isChatCompletion(URI uri) {
        return uri != null && uri.getPath() != null && uri.getPath().contains(ReasoningConstants.CHAT_COMPLETIONS_PATH);
    }

    /**
     * 把已经读过的响应正文再交还给调用方。
     */
    private static final class ReplayClientHttpResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;

        private final byte[] payload;

        private ReplayClientHttpResponse(ClientHttpResponse delegate, byte[] payload) {
            this.delegate = delegate;
            this.payload = payload;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(payload);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
