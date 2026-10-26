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
 * 客户端回放帧。和观测事件不是同一条日志。
 */
public enum TurnFrameTypeEnum {

    /** 回答文本，可能是一批 token */
    ANSWER("ANSWER", "answer"),

    /** 本轮正常结束 */
    DONE("DONE", "done"),

    /** 本轮失败或被取消 */
    ERROR("ERROR", "error"),

    /** 崩溃后续跑已开始，客户端应丢掉本轮半截气泡 */
    TURN_RESTARTED("TURN_RESTARTED", "turn restarted");

    private final String code;
    private final String desc;

    TurnFrameTypeEnum(String code, String desc) {
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
     * 订阅者看到后可以结束跟随。
     *
     * @return DONE 或 ERROR
     */
    public boolean isTerminal() {
        return this == DONE || this == ERROR;
    }

    /**
     * 根据存储 code 反查枚举。
     *
     * @param code 存储值
     * @return 对应枚举
     */
    public static TurnFrameTypeEnum of(String code) {
        for (TurnFrameTypeEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        throw new IllegalArgumentException("unknown TurnFrameTypeEnum code: " + code);
    }
}
