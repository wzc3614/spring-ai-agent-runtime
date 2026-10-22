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
package com.agentruntime.core.concurrency.parallel;

/**
 * 并行任务执行结果包装：把成功 / 失败 / 超时都装进结果，使单任务失败不再以抛异常方式
 * 连累同批兄弟任务。{@link ParallelToolExecutor} 的带超时重载返回 {@code List<TaskResult<T>>}。
 *
 * @param <T> 结果值类型
 */
public final class TaskResult<T> {

    private final boolean success;

    private final boolean timedOut;

    private final T value;

    private final Throwable error;

    private TaskResult(boolean success, boolean timedOut, T value, Throwable error) {
        this.success = success;
        this.timedOut = timedOut;
        this.value = value;
        this.error = error;
    }

    /** 成功结果。 */
    public static <T> TaskResult<T> ok(T value) {
        return new TaskResult<>(true, false, value, null);
    }

    /** 失败结果（非超时）。 */
    public static <T> TaskResult<T> fail(Throwable error) {
        return new TaskResult<>(false, false, null, error);
    }

    /** 超时结果。 */
    public static <T> TaskResult<T> timeout() {
        return new TaskResult<>(false, true, null, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isTimedOut() {
        return timedOut;
    }

    public T getValue() {
        return value;
    }

    public Throwable getError() {
        return error;
    }
}
