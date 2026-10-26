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
package com.agentruntime.core.springai;

/**
 * 工具真正开始执行前通知轮次执行层。非幂等工具会使这一轮不能自动重跑。
 */
public final class TurnToolStartHook {

    /** 收到一次工具开始。参数为该工具是否幂等 */
    @FunctionalInterface
    public interface Recorder {

        /**
         * @param idempotent 工具元数据上的幂等标记
         */
        void onStart(boolean idempotent);
    }

    private static final InheritableThreadLocal<Recorder> CURRENT = new InheritableThreadLocal<>();

    private TurnToolStartHook() {
    }

    /**
     * @param recorder 记录器，null 表示清除
     */
    public static void set(Recorder recorder) {
        if (recorder == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(recorder);
    }

    /**
     * 工具开始时调用。没有记录器时直接返回。
     *
     * @param idempotent 是否幂等
     */
    public static void record(boolean idempotent) {
        Recorder recorder = CURRENT.get();
        if (recorder == null) {
            return;
        }
        recorder.onStart(idempotent);
    }

    /**
     * 清除当前线程上的记录器。
     */
    public static void clear() {
        CURRENT.remove();
    }
}
