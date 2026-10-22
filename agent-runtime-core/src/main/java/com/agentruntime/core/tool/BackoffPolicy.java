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
 * 重试退避策略：FIXED 固定间隔 / LINEAR 线性增长 / EXP 指数退避（带上限）。
 * 供 {@link GovernedToolCallback} 在 TRANSIENT 重试间隔使用。无参构造 + setter，
 * 并提供常用静态工厂。
 */
public class BackoffPolicy {

    /** 退避类型 */
    public enum Type {
        /** 固定间隔 */
        FIXED,
        /** 线性增长：initial * multiplier * attempt */
        LINEAR,
        /** 指数退避：initial * multiplier^(attempt-1)，受 maxIntervalMs 封顶 */
        EXP
    }

    /** 退避类型，默认 FIXED */
    private Type type = Type.FIXED;

    /** 首次退避毫秒 */
    private long initialMs = 200L;

    /** EXP/LINEAR 增长系数 */
    private double multiplier = 2.0d;

    /** 退避上限毫秒，EXP 下生效 */
    private long maxIntervalMs = 2000L;

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
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

    /**
     * 计算第 {@code attempt} 次（从 1 开始）重试前的退避毫秒。
     *
     * @param attempt 即将执行的重试轮次（1 表示第一次重试）
     * @return 退避毫秒，不小于 0
     */
    public long nextDelayMs(int attempt) {
        long base = Math.max(0L, initialMs);
        if (type == Type.LINEAR) {
            return (long) (base * Math.max(1.0d, multiplier) * attempt);
        }
        if (type == Type.EXP) {
            double factor = Math.pow(Math.max(1.0d, multiplier), Math.max(0, attempt - 1));
            long delay = (long) (base * factor);
            return Math.min(delay, Math.max(0L, maxIntervalMs));
        }
        return base;
    }

    /** 固定间隔退避。 */
    public static BackoffPolicy fixed(long initialMs) {
        BackoffPolicy policy = new BackoffPolicy();
        policy.setType(Type.FIXED);
        policy.setInitialMs(initialMs);
        return policy;
    }

    /** 指数退避（带上限）。 */
    public static BackoffPolicy exp(long initialMs, double multiplier, long maxIntervalMs) {
        BackoffPolicy policy = new BackoffPolicy();
        policy.setType(Type.EXP);
        policy.setInitialMs(initialMs);
        policy.setMultiplier(multiplier);
        policy.setMaxIntervalMs(maxIntervalMs);
        return policy;
    }
}
