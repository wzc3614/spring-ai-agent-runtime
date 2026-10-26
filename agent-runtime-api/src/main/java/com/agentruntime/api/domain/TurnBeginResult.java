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

/**
 * 开轮结果。复用时 {@link #turn} 是已有的未完成轮，不是本次传入的草稿。
 */
public class TurnBeginResult {

    /** 会话上已经有未完成轮 */
    private boolean reused;

    /** 本次要执行或跟随的轮次 */
    private TurnRecord turn;

    public boolean isReused() {
        return reused;
    }

    public void setReused(boolean reused) {
        this.reused = reused;
    }

    public TurnRecord getTurn() {
        return turn;
    }

    public void setTurn(TurnRecord turn) {
        this.turn = turn;
    }
}
