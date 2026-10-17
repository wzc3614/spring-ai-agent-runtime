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
 * 审批状态枚举，对应 agent_approval_record.status 列。
 */
public enum ApprovalStatusEnum {

    /** 待审批 */
    PENDING("PENDING", "pending"),

    /** 已批准 */
    APPROVED("APPROVED", "approved"),

    /** 已拒绝 */
    REJECTED("REJECTED", "rejected"),

    /** 超时未处理 */
    EXPIRED("EXPIRED", "expired");

    private final String code;
    private final String desc;

    ApprovalStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public String getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static ApprovalStatusEnum of(String code) {
        for (ApprovalStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown ApprovalStatusEnum code: " + code);
    }
}
