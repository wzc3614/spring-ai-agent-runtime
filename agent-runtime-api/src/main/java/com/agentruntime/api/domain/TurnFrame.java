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
package com.agentruntime.api.domain;

import com.agentruntime.api.enums.TurnFrameTypeEnum;

/**
 * 一条可回放的轮次帧。seq 在本轮内从 1 单调递增。
 */
public class TurnFrame {

    /** 所属轮次 */
    private String turnId;

    /** 本轮单调序号 */
    private long seq;

    /** 帧类型 */
    private TurnFrameTypeEnum type;

    /** 文本。DONE 为空，ERROR 为错误码 */
    private String text;

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public TurnFrameTypeEnum getType() {
        return type;
    }

    public void setType(TurnFrameTypeEnum type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
