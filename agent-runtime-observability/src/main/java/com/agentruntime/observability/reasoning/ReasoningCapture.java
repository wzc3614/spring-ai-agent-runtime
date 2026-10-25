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
package com.agentruntime.observability.reasoning;

import io.micrometer.context.ContextRegistry;

/**
 * 把当前模型调用的 {@link ReasoningBuffer} 绑到线程上，供同步 HTTP 拦截器读取。
 * 流式路径同时放进 Reactor Context，避免 reactor 线程看不到 ThreadLocal。
 */
public final class ReasoningCapture {

    /** Reactor Context 与 context-propagation 使用的 key */
    public static final String CONTEXT_KEY = "agentRuntime.reasoning";

    private static final ThreadLocal<ReasoningBuffer> CURRENT = new ThreadLocal<>();

    private ReasoningCapture() {
    }

    /**
     * 注册 ThreadLocal，让自动上下文传播能把它带到 reactor 线程。
     *
     * @param registry micrometer context registry
     */
    public static void registerThreadLocalAccessor(ContextRegistry registry) {
        registry.registerThreadLocalAccessor(CONTEXT_KEY, ReasoningCapture::current, ReasoningCapture::bind,
                ReasoningCapture::clear);
    }

    /**
     * 开始一次模型调用的采集。
     *
     * @return 这次调用专用的缓冲
     */
    public static ReasoningBuffer open() {
        ReasoningBuffer buffer = new ReasoningBuffer();
        CURRENT.set(buffer);
        return buffer;
    }

    /**
     * @return 当前线程绑定的缓冲。未开始采集时为空
     */
    public static ReasoningBuffer current() {
        return CURRENT.get();
    }

    /**
     * 绑定或清除当前线程的缓冲。context-propagation 恢复上下文时调用。
     *
     * @param buffer 缓冲。空表示清除
     */
    public static void bind(ReasoningBuffer buffer) {
        if (buffer == null) {
            CURRENT.remove();
            return;
        }
        CURRENT.set(buffer);
    }

    /**
     * 往当前线程的缓冲追加一段。没有绑定缓冲时忽略，避免把别的 HTTP 响应写进来。
     *
     * @param chunk 片段
     */
    public static void append(String chunk) {
        ReasoningBuffer buffer = CURRENT.get();
        if (buffer == null) {
            return;
        }
        buffer.append(chunk);
    }

    /**
     * 清除当前线程的缓冲。
     */
    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 只有当前线程仍绑着这次调用的缓冲时才清除，避免清掉下一次调用。
     *
     * @param buffer 这次调用的缓冲
     */
    public static void clearIfCurrent(ReasoningBuffer buffer) {
        if (buffer != null && buffer == CURRENT.get()) {
            CURRENT.remove();
        }
    }
}
