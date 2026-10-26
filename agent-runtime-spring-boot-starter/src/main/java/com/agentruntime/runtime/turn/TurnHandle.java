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
package com.agentruntime.runtime.turn;

import com.agentruntime.api.domain.TurnFrame;
import com.agentruntime.api.enums.TurnFrameTypeEnum;

import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 一次流式轮次的句柄。帧流和 HTTP 连接分开，取消订阅只结束这一次跟随。
 */
public class TurnHandle {

    private final String turnId;

    private final boolean reused;

    private final Flux<TurnFrame> frames;

    public TurnHandle(String turnId, boolean reused, Flux<TurnFrame> frames) {
        this.turnId = turnId;
        this.reused = reused;
        this.frames = frames;
    }

    /**
     * 轮次执行关闭时，把原来的文本流包成帧，不落 Redis。
     *
     * @param turnId 仅供这次响应使用的 ID
     * @param tokens 文本流
     * @return 句柄
     */
    public static TurnHandle live(String turnId, Flux<String> tokens) {
        AtomicLong seq = new AtomicLong();
        Flux<TurnFrame> frames = tokens
                .map(text -> frame(turnId, seq.incrementAndGet(), TurnFrameTypeEnum.ANSWER, text))
                .concatWith(Flux.defer(() -> Flux.just(
                        frame(turnId, seq.incrementAndGet(), TurnFrameTypeEnum.DONE, ""))))
                .onErrorResume(ex -> Flux.just(
                        frame(turnId, seq.incrementAndGet(), TurnFrameTypeEnum.ERROR, ex.getClass().getSimpleName())));
        return new TurnHandle(turnId, false, frames);
    }

    public String getTurnId() {
        return turnId;
    }

    public boolean isReused() {
        return reused;
    }

    public Flux<TurnFrame> frames() {
        return frames;
    }

    private static TurnFrame frame(String turnId, long seq, TurnFrameTypeEnum type, String text) {
        TurnFrame frame = new TurnFrame();
        frame.setTurnId(turnId);
        frame.setSeq(seq);
        frame.setType(type);
        frame.setText(text == null ? "" : text);
        return frame;
    }
}
