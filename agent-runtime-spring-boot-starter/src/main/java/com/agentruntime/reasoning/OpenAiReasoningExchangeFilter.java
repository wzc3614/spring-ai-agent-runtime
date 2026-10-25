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

import com.agentruntime.observability.reasoning.ReasoningBuffer;
import com.agentruntime.observability.reasoning.ReasoningCapture;
import com.agentruntime.observability.reasoning.ReasoningConstants;
import com.agentruntime.observability.reasoning.SseReasoningSplitter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.BodyExtractors;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 从流式聊天补全里拼接 {@code reasoning_content} 增量。原始字节仍原样交给 Spring AI。
 */
public class OpenAiReasoningExchangeFilter implements ExchangeFilterFunction {

    private final ObjectMapper objectMapper;

    public OpenAiReasoningExchangeFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return next.exchange(request).flatMap(response -> Mono.deferContextual(context -> {
            if (!isChatCompletion(request) || !isEventStream(response)) {
                return Mono.just(response);
            }
            ReasoningBuffer buffer = context.<ReasoningBuffer>getOrEmpty(ReasoningCapture.CONTEXT_KEY)
                    .orElse(ReasoningCapture.current());
            if (buffer == null) {
                return Mono.just(response);
            }
            SseReasoningSplitter splitter = new SseReasoningSplitter(objectMapper);
            Flux<DataBuffer> body = response.body(BodyExtractors.toDataBuffers())
                    .doOnNext(dataBuffer -> splitter.accept(textOf(dataBuffer), buffer));
            return Mono.just(ClientResponse.from(response).body(body).build());
        }));
    }

    private boolean isChatCompletion(ClientRequest request) {
        String path = request.url().getPath();
        return path != null && path.contains(ReasoningConstants.CHAT_COMPLETIONS_PATH);
    }

    private boolean isEventStream(ClientResponse response) {
        return response.headers().contentType()
                .filter(MediaType.TEXT_EVENT_STREAM::isCompatibleWith)
                .isPresent();
    }

    private String textOf(DataBuffer dataBuffer) {
        byte[] copy = new byte[dataBuffer.readableByteCount()];
        int position = dataBuffer.readPosition();
        dataBuffer.read(copy);
        dataBuffer.readPosition(position);
        return new String(copy, ReasoningConstants.BODY_CHARSET);
    }
}
