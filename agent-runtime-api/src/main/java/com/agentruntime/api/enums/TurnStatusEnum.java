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
 * 一轮用户请求的执行状态。同一会话同时只允许一个未完成轮次。
 */
public enum TurnStatusEnum {

    /** 已占住会话，模型尚未开始 */
    ACCEPTED("ACCEPTED", "accepted"),

    /** 执行中，持有租约 */
    RUNNING("RUNNING", "running"),

    /** 正常结束 */
    COMPLETED("COMPLETED", "completed"),

    /** 执行失败或拒绝重跑 */
    FAILED("FAILED", "failed"),

    /** 客户端断开后按配置取消 */
    CANCELLED("CANCELLED", "cancelled");

    private final String code;
    private final String desc;

    TurnStatusEnum(String code, String desc) {
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
     * 未完成，占用会话的单飞槽位。
     *
     * @return ACCEPTED 或 RUNNING
     */
    public boolean isOpen() {
        return this == ACCEPTED || this == RUNNING;
    }

    /**
     * 根据存储 code 反查枚举。
     *
     * @param code 存储值
     * @return 对应枚举
     */
    public static TurnStatusEnum of(String code) {
        for (TurnStatusEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown TurnStatusEnum code: " + code);
    }
}
