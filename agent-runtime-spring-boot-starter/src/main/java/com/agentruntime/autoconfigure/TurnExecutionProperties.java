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

import com.agentruntime.api.turn.TurnExecutionConstants;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 轮次执行配置。绑定 {@code agent.runtime.turn-execution.*}。
 */
@ConfigurationProperties(prefix = TurnExecutionConstants.PROPERTY_PREFIX)
public class TurnExecutionProperties {

    /** 总开关。关掉后 stream 保持原来的文本流 */
    private boolean enabled = true;

    /** 扫描过期租约并整轮重跑。没有 Redis 时不要打开 */
    private boolean crashRecoveryEnabled = true;

    /** 最后一个订阅者离开时取消本轮。默认继续跑完 */
    private boolean cancelOnClientDisconnect = false;

    /** 租约时长（毫秒） */
    private long leaseTtlMillis = TurnExecutionConstants.DEFAULT_LEASE_TTL_MILLIS;

    /** 心跳间隔（毫秒） */
    private long renewIntervalMillis = TurnExecutionConstants.DEFAULT_RENEW_INTERVAL_MILLIS;

    /** 恢复扫描间隔（毫秒） */
    private long recoveryIntervalMillis = TurnExecutionConstants.DEFAULT_RECOVERY_INTERVAL_MILLIS;

    /** 回答事件凑够这么多条就写一次 */
    private int eventBatchSize = TurnExecutionConstants.DEFAULT_EVENT_BATCH_SIZE;

    /** 回答事件最长等待（毫秒） */
    private long eventBatchIntervalMillis = TurnExecutionConstants.DEFAULT_EVENT_BATCH_INTERVAL_MILLIS;

    /** 回放流保留时间（秒） */
    private long eventTtlSeconds = TurnExecutionConstants.DEFAULT_EVENT_TTL_SECONDS;

    /** 回放流近似最大长度 */
    private int eventMaxLen = TurnExecutionConstants.DEFAULT_EVENT_MAX_LEN;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isCrashRecoveryEnabled() {
        return crashRecoveryEnabled;
    }

    public void setCrashRecoveryEnabled(boolean crashRecoveryEnabled) {
        this.crashRecoveryEnabled = crashRecoveryEnabled;
    }

    public boolean isCancelOnClientDisconnect() {
        return cancelOnClientDisconnect;
    }

    public void setCancelOnClientDisconnect(boolean cancelOnClientDisconnect) {
        this.cancelOnClientDisconnect = cancelOnClientDisconnect;
    }

    public long getLeaseTtlMillis() {
        return leaseTtlMillis;
    }

    public void setLeaseTtlMillis(long leaseTtlMillis) {
        this.leaseTtlMillis = leaseTtlMillis;
    }

    public long getRenewIntervalMillis() {
        return renewIntervalMillis;
    }

    public void setRenewIntervalMillis(long renewIntervalMillis) {
        this.renewIntervalMillis = renewIntervalMillis;
    }

    public long getRecoveryIntervalMillis() {
        return recoveryIntervalMillis;
    }

    public void setRecoveryIntervalMillis(long recoveryIntervalMillis) {
        this.recoveryIntervalMillis = recoveryIntervalMillis;
    }

    public int getEventBatchSize() {
        return eventBatchSize;
    }

    public void setEventBatchSize(int eventBatchSize) {
        this.eventBatchSize = eventBatchSize;
    }

    public long getEventBatchIntervalMillis() {
        return eventBatchIntervalMillis;
    }

    public void setEventBatchIntervalMillis(long eventBatchIntervalMillis) {
        this.eventBatchIntervalMillis = eventBatchIntervalMillis;
    }

    public long getEventTtlSeconds() {
        return eventTtlSeconds;
    }

    public void setEventTtlSeconds(long eventTtlSeconds) {
        this.eventTtlSeconds = eventTtlSeconds;
    }

    public int getEventMaxLen() {
        return eventMaxLen;
    }

    public void setEventMaxLen(int eventMaxLen) {
        this.eventMaxLen = eventMaxLen;
    }
}
