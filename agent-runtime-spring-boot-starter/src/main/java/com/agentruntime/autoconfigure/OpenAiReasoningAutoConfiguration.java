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
package com.agentruntime.autoconfigure;

import com.agentruntime.reasoning.OpenAiReasoningInterceptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;

/**
 * 给 OpenAI 兼容客户端的同步调用补上 {@code reasoning_content} 采集。没有 OpenAI 客户端时不装配。
 */
@AutoConfiguration
@ConditionalOnClass(name = {
        "org.springframework.ai.openai.api.OpenAiApi",
        "org.springframework.http.client.ClientHttpRequestInterceptor"
})
public class OpenAiReasoningAutoConfiguration {

    /**
     * 同步响应拦截器。
     *
     * @param objectMapper JSON 解析器
     * @return 拦截器
     */
    @Bean
    @ConditionalOnMissingBean
    public OpenAiReasoningInterceptor openAiReasoningInterceptor(ObjectMapper objectMapper) {
        return new OpenAiReasoningInterceptor(objectMapper);
    }

    /**
     * 挂到自动配置的 RestClient 上。克隆出去的 builder 会带上同一个拦截器。
     *
     * @param interceptor 拦截器
     * @return 定制器
     */
    @Bean
    public RestClientCustomizer openAiReasoningRestClientCustomizer(OpenAiReasoningInterceptor interceptor) {
        return builder -> builder.requestInterceptor(interceptor);
    }
}
