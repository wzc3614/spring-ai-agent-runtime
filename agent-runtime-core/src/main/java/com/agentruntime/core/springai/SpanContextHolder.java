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
 * Span 上下文持有者。编排器在调用前设置当前 TURN 的 spanId 与 sessionId，供
 * {@code ObservingChatModel}（每次 LLM 调用建 LlmCallSpan）和工具装饰器（建 ToolCallSpan）
 * 读取父 span。
 *
 * <p>使用 {@link InheritableThreadLocal}：并行工具调用经 {@code ParallelToolExecutor} 在请求线程
 * 上创建虚拟线程执行，子线程需继承父线程的 TURN spanId + sessionId 才能正确挂 TOOL_CALL span
 * 子树。{@code newVirtualThreadPerTaskExecutor} 每任务新建虚拟线程不复用，无跨任务脏读风险。
 *
 * <p>流式调用路径下，模型 stream 与工具执行跑在 reactor 线程上，{@link InheritableThreadLocal}
 * 无法跨线程传递。故将两个字段注册到 micrometer context-propagation，配合
 * {@code Hooks.enableAutomaticContextPropagation()} 后，编排器经 {@code contextWrite} 写入的
 * spanId/sessionId 会在各 reactor 操作线程上自动恢复到 ThreadLocal，使工具装饰器在流式下也能正确建 span。
 */
public final class SpanContextHolder {

    /** context-propagation 中 parentSpanId 的 key。 */
    public static final String CONTEXT_KEY_PARENT_SPAN_ID = "agentRuntime.parentSpanId";

    /** context-propagation 中 sessionId 的 key。 */
    public static final String CONTEXT_KEY_SESSION_ID = "agentRuntime.sessionId";

    private static final InheritableThreadLocal<String> PARENT_SPAN_ID = new InheritableThreadLocal<>();

    private static final InheritableThreadLocal<String> SESSION_ID = new InheritableThreadLocal<>();

    private SpanContextHolder() {
    }

    /**
     * 将 parentSpanId / sessionId 两个 ThreadLocal 注册到 context-propagation registry，
     * 使其在启用自动上下文传播后可随 Reactor Context 跨线程透传。幂等，重复注册无副作用。
     *
     * @param registry micrometer context registry
     */
    public static void registerThreadLocalAccessors(ContextRegistry registry) {
        registry.registerThreadLocalAccessor(CONTEXT_KEY_PARENT_SPAN_ID, PARENT_SPAN_ID);
        registry.registerThreadLocalAccessor(CONTEXT_KEY_SESSION_ID, SESSION_ID);
    }

    /**
     * 设置当前上下文。
     *
     * @param parentSpanId 父 span ID（TURN span）
     * @param sessionId    会话 ID
     */
    public static void set(String parentSpanId, String sessionId) {
        PARENT_SPAN_ID.set(parentSpanId);
        SESSION_ID.set(sessionId);
    }

    /**
     * @return 当前父 span ID（可能为 null）
     */
    public static String parentSpanId() {
        return PARENT_SPAN_ID.get();
    }

    /**
     * @return 当前会话 ID（可能为 null）
     */
    public static String sessionId() {
        return SESSION_ID.get();
    }

    /**
     * 清除当前线程上下文。
     */
    public static void clear() {
        PARENT_SPAN_ID.remove();
        SESSION_ID.remove();
    }
}
