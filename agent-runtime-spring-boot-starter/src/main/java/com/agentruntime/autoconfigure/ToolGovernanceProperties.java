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

import com.agentruntime.core.tool.BackoffPolicy;
import com.agentruntime.core.tool.ToolGovernanceDefaults;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 工具治理配置。绑定 {@code agent.runtime.tool-governance.*}，
 * 在自动装配时转换为 {@link ToolGovernanceDefaults} 注入到调用链。
 *
 * <p>默认值保守：默认不重试（{@code default-max-attempts=1}），单调用总超时 30s，
 * 退避固定 200ms。需要重试的工具须显式在 {@link com.agentruntime.core.tool.ToolMeta}
 * 中声明幂等 + {@code maxAttempts>1}。
 */
@ConfigurationProperties(prefix = "agent.runtime.tool-governance")
public class ToolGovernanceProperties {

    /** 默认单次调用总超时（毫秒） */
    private long defaultTimeoutMs = 30000L;

    /** 默认最大尝试次数（1 即不重试） */
    private int defaultMaxAttempts = 1;

    /** 默认退避策略 */
    private Backoff backoff = new Backoff();

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

    public Backoff getBackoff() {
        return backoff;
    }

    public void setBackoff(Backoff backoff) {
        this.backoff = backoff;
    }

    /** 退避策略配置 */
    public static class Backoff {

        /** 退避类型：FIXED / LINEAR / EXP */
        private BackoffPolicy.Type type = BackoffPolicy.Type.FIXED;

        /** 首次退避毫秒 */
        private long initialMs = 200L;

        /** EXP/LINEAR 增长系数 */
        private double multiplier = 2.0d;

        /** 退避上限毫秒（EXP 下生效） */
        private long maxIntervalMs = 2000L;

        public BackoffPolicy.Type getType() {
            return type;
        }

        public void setType(BackoffPolicy.Type type) {
            this.type = type;
        }

        public long getInitialMs() {
            return initialMs;
        }

        public void setInitialMs(long initialMs) {
            this.initialMs = initialMs;
        }

        public double getMultiplier() {
            return multiplier;
        }

        public void setMultiplier(double multiplier) {
            this.multiplier = multiplier;
        }

        public long getMaxIntervalMs() {
            return maxIntervalMs;
        }

        public void setMaxIntervalMs(long maxIntervalMs) {
            this.maxIntervalMs = maxIntervalMs;
        }
    }

    /** 转换为 core 侧 {@link ToolGovernanceDefaults}。 */
    public ToolGovernanceDefaults toDefaults() {
        ToolGovernanceDefaults defaults = new ToolGovernanceDefaults();
        defaults.setDefaultTimeoutMs(defaultTimeoutMs);
        defaults.setDefaultMaxAttempts(Math.max(1, defaultMaxAttempts));
        defaults.setDefaultBackoff(toBackoffPolicy(backoff));
        return defaults;
    }

    private static BackoffPolicy toBackoffPolicy(Backoff b) {
        BackoffPolicy policy = new BackoffPolicy();
        policy.setType(b.getType());
        policy.setInitialMs(b.getInitialMs());
        policy.setMultiplier(b.getMultiplier());
        policy.setMaxIntervalMs(b.getMaxIntervalMs());
        return policy;
    }
}
