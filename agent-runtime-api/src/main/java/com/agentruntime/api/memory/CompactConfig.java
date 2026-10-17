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

/**
 * 记忆压缩配置，对应动态预算计算公式中的各项参数。
 */
public class CompactConfig {

    /** 上下文窗口大小（token） */
    private int contextWindow;

    /** 预留输出 token，默认 8000 */
    private int reservedOutput = 8000;

    /** 安全缓冲 token，默认 3000 */
    private int safetyBuffer = 3000;

    /** 触发压缩阈值，默认 0.90 */
    private double threshold = 0.90D;

    /** 压缩用模型名（第三层 LLM 摘要使用，M1 暂存） */
    private String model;

    /**
     * 工厂方法：按模型名构建配置，预算参数取默认值。
     *
     * @param model 压缩用模型名
     * @return 配置
     */
    public static CompactConfig ofModel(String model) {
        CompactConfig config = new CompactConfig();
        config.setContextWindow(200000);
        config.setModel(model);
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

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
