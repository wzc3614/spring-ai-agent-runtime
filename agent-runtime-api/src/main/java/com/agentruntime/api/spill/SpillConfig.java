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
package com.agentruntime.api.spill;

/**
 * Spill 溢出保护配置。
 */
public class SpillConfig {

    /** 触发溢出的字符阈值，默认 32000 */
    private int thresholdChars = 32000;

    /** 预览长度，默认 2048 */
    private int previewLength = 2048;

    /** 对象 key 命名空间前缀 */
    private String namespacePrefix;

    public int getThresholdChars() {
        return thresholdChars;
    }

    public void setThresholdChars(int thresholdChars) {
        this.thresholdChars = thresholdChars;
    }

    public int getPreviewLength() {
        return previewLength;
    }

    public void setPreviewLength(int previewLength) {
        this.previewLength = previewLength;
    }

    public String getNamespacePrefix() {
        return namespacePrefix;
    }

    public void setNamespacePrefix(String namespacePrefix) {
        this.namespacePrefix = namespacePrefix;
    }
}
