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
package com.agentruntime.runtime.hook;

/**
 * 一轮对话上的业务钩子。{@link #onPrepare} 与 Skill 准备并行；
 * {@link #beforePrompt} 在组装 prompt、调用模型之前执行。
 *
 * <p>任一阶段调用 {@link TurnHookContext#interrupt} 都会结束本轮并把回复交给调用方，
 * 模型不会被调用。{@code call} 与 {@code stream} 走同一道门。
 * 钩子抛出的异常会失败关闭本轮，并向外传播。
 *
 * <p>多个钩子按注册顺序执行。同一阶段里先打断的钩子生效后，后续钩子不再调用。
 */
public interface TurnHook {

    /**
     * Skill 准备进行中时执行。适合做本地快速判断，并把远程调用的 Future 放进上下文。
     * 此方法返回前不要等待那个远程调用。
     *
     * @param context 本轮上下文。此时还没有 Skill 计划
     */
    default void onPrepare(TurnHookContext context) {
    }

    /**
     * Skill 准备已经结束、prompt 尚未组装时执行。适合在这里等待第一阶段发起的远程调用。
     *
     * @param context 本轮上下文。{@link TurnHookContext#getPlan()} 已可读
     */
    default void beforePrompt(TurnHookContext context) {
    }
}
