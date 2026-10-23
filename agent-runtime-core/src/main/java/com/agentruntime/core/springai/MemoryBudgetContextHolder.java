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

import io.micrometer.context.ContextRegistry;

/**
 * 记忆预算上下文持有者。编排器在调用 {@code ChatClient} 前把当前轮的固定开销
 * （系统提示词 + 工具 schema + 当前用户输入的 token 估算）写入本 holder，
 * 供 {@code CompactingChatMemory.get()} 在注入模型前计算动态预算时读取。
 *
 * <p>使用 {@link InheritableThreadLocal}：并行工具调用经 {@code ParallelToolExecutor} 在请求线程
 * 上创建虚拟线程执行，子线程需继承父线程的预算上下文。流式路径下经 context-propagation
 * 注册后，可随 Reactor Context 跨线程透传（与 {@link SpanContextHolder} 同模式）。
 */
public final class MemoryBudgetContextHolder {

    /** context-propagation 中 fixedOverhead 的 key。 */
    public static final String CONTEXT_KEY_FIXED_OVERHEAD = "agentRuntime.fixedOverhead";

    private static final InheritableThreadLocal<Integer> FIXED_OVERHEAD = new InheritableThreadLocal<>();

    private MemoryBudgetContextHolder() {
    }

    /**
     * 将 fixedOverhead 注册到 context-propagation registry，使其在启用自动上下文传播后
     * 可随 Reactor Context 跨线程透传。幂等，重复注册无副作用。
     *
     * @param registry micrometer context registry
     */
    public static void registerThreadLocalAccessor(ContextRegistry registry) {
        registry.registerThreadLocalAccessor(CONTEXT_KEY_FIXED_OVERHEAD, FIXED_OVERHEAD);
    }

    /**
     * 设置当前轮的固定开销（token）。
     *
     * @param fixedOverhead 固定开销
     */
    public static void set(int fixedOverhead) {
        FIXED_OVERHEAD.set(fixedOverhead);
    }

    /**
     * @return 当前轮固定开销（未设置时返回 0）
     */
    public static int fixedOverhead() {
        Integer value = FIXED_OVERHEAD.get();
        return value == null ? 0 : value;
    }

    /**
     * 清除当前线程上下文。
     */
    public static void clear() {
        FIXED_OVERHEAD.remove();
    }
}
