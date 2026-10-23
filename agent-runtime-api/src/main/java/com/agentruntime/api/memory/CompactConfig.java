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
package com.agentruntime.api.memory;

import java.util.List;

/**
 * 记忆压缩配置，对应动态预算计算公式中的各项参数。
 *
 * <p>压缩用模型不在此配置，统一由用户注入的 {@code ChatModel} 提供（经
 * {@code CompactingChatMemoryFactory.builder(chatModel)} 传入，供默认 LLM 摘要器使用）。
 */
public class CompactConfig {

    /** 上下文窗口大小（token） */
    private int contextWindow;

    /** 预留输出 token，默认 8000 */
    private int reservedOutput = 8000;

    /** 安全缓冲 token，默认 3000 */
    private int safetyBuffer = 3000;

    /** 触发压缩阈值，默认 0.90（预估总量超过有效窗口 × 阈值即触发） */
    private double threshold = 0.90D;

    /** 压缩时保留最近多少条原文不参与摘要，默认 6 */
    private int keepRecentMessages = 6;

    /** 历史占用达到 historyBudget × 此比例即触发异步压缩，默认 0.85 */
    private double summaryTriggerRatio = 0.85D;

    /** 单 session 压缩锁 TTL（毫秒），需大于单次 LLM 摘要调用预估耗时，默认 120000 */
    private long summaryLockTtlMillis = 120000L;

    /** 待压缩段消息数少于该值时不触发摘要（避免无意义调用），默认 8 */
    private int minMessagesToCompact = 8;

    /** 非锚点候选 Turn 的 top-K 上限（打分后截断），默认 12 */
    private int topK = 12;

    /** 最近强制入选保护窗口（候选中最近 N 条必入选，即使未进 top-K），默认 4 */
    private int minProtectWindow = 4;

    /** 锚点判定与打分用的关键词集合（命中即加分 / 判为锚点） */
    private List<String> anchorKeywords = List.of("决定", "选择", "采用", "确认", "方案", "目标", "预算", "约束");

    /** Decision 锚点判定关键词（assistant 内容命中即视为含决策的 Turn） */
    private List<String> decisionKeywords = List.of("决定", "选择", "采用", "确认方案");

    /** 是否启用第一层结构化提取（false 时跳过提取，直接增量摘要），默认 true */
    private boolean extractionEnabled = true;

    /** 单次 LLM 调用超时（毫秒），超时后按阶段降级，默认 60000 */
    private long llmCallTimeoutMillis = 60000L;

    /** 超时或非法 JSON 的重试次数（不含首次），默认 1 */
    private int maxRetries = 1;

    /**
     * 工厂方法：构建默认配置，预算参数取默认值。
     *
     * @return 默认配置
     */
    public static CompactConfig defaults() {
        CompactConfig config = new CompactConfig();
        config.setContextWindow(200000);
        return config;
    }

    public int getContextWindow() {
        return contextWindow;
    }

    public void setContextWindow(int contextWindow) {
        this.contextWindow = contextWindow;
    }

    public int getReservedOutput() {
        return reservedOutput;
    }

    public void setReservedOutput(int reservedOutput) {
        this.reservedOutput = reservedOutput;
    }

    public int getSafetyBuffer() {
        return safetyBuffer;
    }

    public void setSafetyBuffer(int safetyBuffer) {
        this.safetyBuffer = safetyBuffer;
    }

    public double getThreshold() {
        return threshold;
    }

    public void setThreshold(double threshold) {
        this.threshold = threshold;
    }

    public int getKeepRecentMessages() {
        return keepRecentMessages;
    }

    public void setKeepRecentMessages(int keepRecentMessages) {
        this.keepRecentMessages = keepRecentMessages;
    }

    public double getSummaryTriggerRatio() {
        return summaryTriggerRatio;
    }

    public void setSummaryTriggerRatio(double summaryTriggerRatio) {
        this.summaryTriggerRatio = summaryTriggerRatio;
    }

    public long getSummaryLockTtlMillis() {
        return summaryLockTtlMillis;
    }

    public void setSummaryLockTtlMillis(long summaryLockTtlMillis) {
        this.summaryLockTtlMillis = summaryLockTtlMillis;
    }

    public int getMinMessagesToCompact() {
        return minMessagesToCompact;
    }

    public void setMinMessagesToCompact(int minMessagesToCompact) {
        this.minMessagesToCompact = minMessagesToCompact;
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        this.topK = topK;
    }

    public int getMinProtectWindow() {
        return minProtectWindow;
    }

    public void setMinProtectWindow(int minProtectWindow) {
        this.minProtectWindow = minProtectWindow;
    }

    public List<String> getAnchorKeywords() {
        return anchorKeywords;
    }

    public void setAnchorKeywords(List<String> anchorKeywords) {
        this.anchorKeywords = anchorKeywords;
    }

    public List<String> getDecisionKeywords() {
        return decisionKeywords;
    }

    public void setDecisionKeywords(List<String> decisionKeywords) {
        this.decisionKeywords = decisionKeywords;
    }

    public boolean isExtractionEnabled() {
        return extractionEnabled;
    }

    public void setExtractionEnabled(boolean extractionEnabled) {
        this.extractionEnabled = extractionEnabled;
    }

    public long getLlmCallTimeoutMillis() {
        return llmCallTimeoutMillis;
    }

    public void setLlmCallTimeoutMillis(long llmCallTimeoutMillis) {
        this.llmCallTimeoutMillis = llmCallTimeoutMillis;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}
