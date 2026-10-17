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
package com.agentruntime.demo;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 演示用业务工具：模拟查询用户信息与近期订单。
 */
@Component
public class BusinessTool {

    /** 模拟下游调用耗时，用于在 demo 中观测并行工具调用的加速效果。 */
    private static final long MOCK_LATENCY_MS = 500L;

    /**
     * 查询用户信息。
     *
     * @param userId 用户 ID
     * @return 用户信息
     */
    @Tool(name = "query_user_info", description = "Query basic profile of a user by userId.")
    public String queryUserInfo(String userId) {
        sleep();
        return "{\"userId\":\"" + userId + "\",\"name\":\"Zhang San\",\"level\":\"VIP\",\"region\":\"CN\"}";
    }

    /**
     * 查询用户近期订单。
     *
     * @param userId 用户 ID
     * @return 订单列表
     */
    @Tool(name = "query_recent_orders", description = "Query recent orders of a user by userId.")
    public String queryRecentOrders(String userId) {
        sleep();
        return "{\"userId\":\"" + userId + "\",\"orders\":[{\"orderId\":\"ORD-1001\",\"status\":\"shipped\"},"
                + "{\"orderId\":\"ORD-1002\",\"status\":\"paid\"}]}";
    }

    private void sleep() {
        try {
            TimeUnit.MILLISECONDS.sleep(MOCK_LATENCY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

