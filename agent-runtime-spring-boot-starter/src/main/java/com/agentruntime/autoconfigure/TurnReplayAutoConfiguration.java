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

import com.agentruntime.api.repository.SessionRepository;
import com.agentruntime.api.turn.TurnExecutionConstants;
import com.agentruntime.api.turn.TurnStore;
import com.agentruntime.runtime.turn.TurnExecutionEngine;
import com.agentruntime.turn.TurnExecutionExceptionHandler;
import com.agentruntime.turn.TurnReplayController;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

/**
 * 轮次回放端点。默认随轮次执行打开，且只在 Web 应用里注册。
 */
@AutoConfiguration
@AutoConfigureAfter(AgentRuntimeAutoConfiguration.class)
@ConditionalOnWebApplication
@ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
@ConditionalOnProperty(name = TurnExecutionConstants.ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
@ConditionalOnBean({TurnExecutionEngine.class, TurnStore.class, SessionRepository.class})
public class TurnReplayAutoConfiguration {

    /**
     * @param turnExecutionEngine 跟随事件
     * @param turnStore           校验轮次归属
     * @param sessionRepository   校验 uid
     * @return 回放控制器
     */
    @Bean
    public TurnReplayController turnReplayController(TurnExecutionEngine turnExecutionEngine, TurnStore turnStore,
                                                      SessionRepository sessionRepository) {
        return new TurnReplayController(turnExecutionEngine, turnStore, sessionRepository);
    }

    /**
     * @return 把轮次异常写成 HTTP 状态
     */
    @Bean
    public TurnExecutionExceptionHandler turnExecutionExceptionHandler() {
        return new TurnExecutionExceptionHandler();
    }
}
