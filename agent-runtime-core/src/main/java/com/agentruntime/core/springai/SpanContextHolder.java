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
 * Span 上下文持有者。编排器在调用前设置当前 TURN 的 spanId 与 sessionId，供
 * {@code ObservingChatModel}（每次 LLM 调用建 LlmCallSpan）和工具装饰器（建 ToolCallSpan）
 * 读取父 span。
 *
 * <p>使用 {@link InheritableThreadLocal}：并行工具调用经 {@code ParallelToolExecutor} 在请求线程
 * 上创建虚拟线程执行，子线程需继承父线程的 TURN spanId + sessionId 才能正确挂 TOOL_CALL span
 * 子树。{@code newVirtualThreadPerTaskExecutor} 每任务新建虚拟线程不复用，无跨任务脏读风险。
 * 仅适用于同步调用路径。
 */
public final class SpanContextHolder {

    private static final InheritableThreadLocal<String> PARENT_SPAN_ID = new InheritableThreadLocal<>();

    private static final InheritableThreadLocal<String> SESSION_ID = new InheritableThreadLocal<>();

    private SpanContextHolder() {
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
