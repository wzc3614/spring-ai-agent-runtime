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

import com.agentruntime.api.repository.EventRepository;
import com.agentruntime.api.repository.SessionRepository;
import com.agentruntime.api.repository.SpanRepository;
import com.agentruntime.observability.trace.TraceTreeRenderer;
import com.agentruntime.trace.TraceController;
import com.agentruntime.trace.TraceEndpointConstants;
import com.agentruntime.trace.TraceTreeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * 单次请求调用树页面。默认关闭，且只在 Web 应用里注册，避免把 spring-web 强加给非 Web 接入方。
 */
@AutoConfiguration
@AutoConfigureAfter(AgentRuntimeAutoConfiguration.class)
@ConditionalOnWebApplication
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
@ConditionalOnProperty(name = TraceEndpointConstants.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = false)
@ConditionalOnBean({SpanRepository.class, EventRepository.class, SessionRepository.class, ObjectMapper.class})
public class TraceAutoConfiguration {

    /**
     * 纯文本渲染器。
     *
     * @param objectMapper 解析 span attributes
     * @return 渲染器
     */
    @Bean
    @ConditionalOnMissingBean
    public TraceTreeRenderer traceTreeRenderer(ObjectMapper objectMapper) {
        return new TraceTreeRenderer(objectMapper);
    }

    /**
     * 从已落库的 span / event 组装一轮调用树。
     *
     * @param sessionRepository 会话仓储
     * @param spanRepository    span 仓储
     * @param eventRepository   event 仓储
     * @param traceTreeRenderer 渲染器
     * @return 查询服务
     */
    @Bean
    @ConditionalOnMissingBean
    public TraceTreeService traceTreeService(SessionRepository sessionRepository, SpanRepository spanRepository,
                                             EventRepository eventRepository, TraceTreeRenderer traceTreeRenderer) {
        return new TraceTreeService(sessionRepository, spanRepository, eventRepository, traceTreeRenderer);
    }

    /**
     * 只读 HTML 端点。
     *
     * @param traceTreeService 查询服务
     * @return 控制器
     */
    @Bean
    @ConditionalOnMissingBean
    public TraceController traceController(TraceTreeService traceTreeService) {
        return new TraceController(traceTreeService);
    }
}
