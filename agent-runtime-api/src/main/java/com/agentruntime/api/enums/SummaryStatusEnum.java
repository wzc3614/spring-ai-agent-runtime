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
package com.agentruntime.api.enums;

/**
 * 摘要快照状态枚举，对应 agent_summary_snapshot.status 列。
 *
 * <p>用于压缩管道降级与回退的可观测：成功 / 提取降级 / 分段合并 / 失败（不覆盖旧摘要、不驱逐 Turn）。
 */
public enum SummaryStatusEnum {

    /** 压缩成功，sections 可用 */
    SUCCESS("SUCCESS", "success"),

    /** 第一层提取失败后降级继续，摘要仍可用 */
    DEGRADED("DEGRADED", "degraded"),

    /** 部分成功，分段摘要后合并，可能丢失部分细节 */
    PARTIAL("PARTIAL", "partial"),

    /** 压缩失败，不覆盖旧摘要，对应 Turn 不驱逐，等待下一轮重试 */
    FAILED("FAILED", "failed");

    private final String code;
    private final String desc;

    SummaryStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    /**
     * 根据存储 code 反查枚举。
     *
     * @param code 存储值
     * @return 对应枚举
     * @throws IllegalArgumentException 未知的 code
     */
    public static SummaryStatusEnum of(String code) {
        for (SummaryStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown SummaryStatusEnum code: " + code);
    }
}
