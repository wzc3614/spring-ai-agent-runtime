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
package com.agentruntime.core.tool;

/**
 * 工具治理默认值：当 {@link ToolMeta} 的 timeout/maxAttempts/backoff 为 null 时兜底。
 * 放在 core 侧（core 不能依赖 starter 的 @ConfigurationProperties），由 starter 的
 * {@code ToolGovernanceProperties} 转换而来后注入 {@link GovernedToolCallback} 与
 * {@code ParallelToolCallingManager}。
 */
public class ToolGovernanceDefaults {

    /** 默认单次调用总超时（毫秒） */
    private long defaultTimeoutMs = 30000L;

    /** 默认最大尝试次数（1 即不重试） */
    private int defaultMaxAttempts = 1;

    /** 默认退避策略 */
    private BackoffPolicy defaultBackoff = BackoffPolicy.fixed(200L);

    public long getDefaultTimeoutMs() {
        return defaultTimeoutMs;
    }

    public void setDefaultTimeoutMs(long defaultTimeoutMs) {
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    public int getDefaultMaxAttempts() {
        return defaultMaxAttempts;
    }

    public void setDefaultMaxAttempts(int defaultMaxAttempts) {
        this.defaultMaxAttempts = defaultMaxAttempts;
    }

    public BackoffPolicy getDefaultBackoff() {
        return defaultBackoff;
    }

    public void setDefaultBackoff(BackoffPolicy defaultBackoff) {
        this.defaultBackoff = defaultBackoff;
    }
}
