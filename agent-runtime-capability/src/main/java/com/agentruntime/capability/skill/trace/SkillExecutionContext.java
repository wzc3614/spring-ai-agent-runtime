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
package com.agentruntime.capability.skill.trace;

import io.micrometer.context.ContextRegistry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 本轮 Skill 激活期间的工具调用轨迹。
 * 使用 {@link InheritableThreadLocal}，使虚拟线程上的并行工具调用能追加到同一份列表。
 * 流式路径需把该 ThreadLocal 注册到 context-propagation。
 */
public final class SkillExecutionContext {

    /** context-propagation 中轨迹列表的 key。 */
    public static final String CONTEXT_KEY = "agentRuntime.skillTrace";

    private static final InheritableThreadLocal<List<SkillToolTrace>> TRACES = new InheritableThreadLocal<>();

    private SkillExecutionContext() {
    }

    /**
     * 注册到 context-propagation。重复注册由调用方保证只发生一次。
     *
     * @param registry micrometer context registry
     */
    public static void registerThreadLocalAccessor(ContextRegistry registry) {
        registry.registerThreadLocalAccessor(CONTEXT_KEY, TRACES);
    }

    /**
     * 开始收集本轮轨迹。
     *
     * @return 供 Reactor Context 传递的同一份列表
     */
    public static List<SkillToolTrace> start() {
        List<SkillToolTrace> traces = Collections.synchronizedList(new ArrayList<>());
        TRACES.set(traces);
        return traces;
    }

    /**
     * @return 当前线程上的轨迹列表，未开始时为 null
     */
    public static List<SkillToolTrace> current() {
        return TRACES.get();
    }

    /**
     * 追加一次逻辑工具调用。上下文不存在时忽略。
     *
     * @param toolName   工具名
     * @param success    是否成功返回
     * @param durationMs 耗时
     */
    public static void record(String toolName, boolean success, long durationMs) {
        List<SkillToolTrace> traces = TRACES.get();
        if (traces == null) {
            return;
        }
        SkillToolTrace trace = new SkillToolTrace();
        trace.setToolName(toolName);
        trace.setSuccess(success);
        trace.setDurationMs(durationMs);
        traces.add(trace);
    }

    /**
     * @return 当前轨迹的快照
     */
    public static List<SkillToolTrace> snapshot() {
        List<SkillToolTrace> traces = TRACES.get();
        if (traces == null) {
            return List.of();
        }
        synchronized (traces) {
            return List.copyOf(traces);
        }
    }

    /**
     * 清除当前线程的轨迹。
     */
    public static void clear() {
        TRACES.remove();
    }

    static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }
}
