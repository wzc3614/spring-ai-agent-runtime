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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.springframework.util.CollectionUtils;

/**
 * 并行工具执行器：用虚拟线程并发执行多个独立任务，Semaphore 限并发，
 * 结果按入参顺序对齐返回。收编自 demo 的 BoundedAsyncTask 思路。
 *
 * <p>提供两类入口：
 * <ul>
 *   <li>{@link #invoke(List, int)}：原兼容入口，返回 {@code List<T>}，失败抛异常（保留旧行为）。</li>
 *   <li>{@link #invoke(List, int, List)}：带每任务独立超时，返回 {@code List<TaskResult<T>>}，
 *       单任务超时/失败不再连累兄弟任务，超时即 {@code cancel(true)} 中断虚拟线程。</li>
 * </ul>
 */
public class ParallelToolExecutor {

    /**
     * 并发执行一批任务，按入参顺序返回结果。失败以抛异常方式传播（兼容旧调用方）。
     *
     * @param tasks          任务列表
     * @param maxConcurrency 最大并发
     * @param <T>            结果类型
     * @return 与 tasks 顺序对齐的结果列表
     */
    public <T> List<T> invoke(List<Supplier<T>> tasks, int maxConcurrency) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        int permits = Math.max(1, maxConcurrency);
        Semaphore semaphore = new Semaphore(permits);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<T>> futures = new ArrayList<>(tasks.size());
            for (Supplier<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    semaphore.acquire();
                    try {
                        return task.get();
                    } finally {
                        semaphore.release();
                    }
                }));
            }
            List<T> results = new ArrayList<>(tasks.size());
            for (Future<T> future : futures) {
                results.add(await(future));
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    /**
     * 并发执行一批任务，每个任务带独立超时，按入参顺序返回 {@link TaskResult}。
     * 单任务超时/失败被装进结果返回，不再以抛异常方式连累兄弟任务。
     *
     * @param tasks          任务列表
     * @param maxConcurrency 最大并发
     * @param timeouts       与 tasks 等长、顺序对齐的每任务超时；元素为 null 表示该任务不限时
     * @param <T>            结果类型
     * @return 与 tasks 顺序对齐的结果列表（成功/失败/超时）
     */
    public <T> List<TaskResult<T>> invoke(List<Supplier<T>> tasks, int maxConcurrency, List<Duration> timeouts) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        int permits = Math.max(1, maxConcurrency);
        Semaphore semaphore = new Semaphore(permits);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<T>> futures = new ArrayList<>(tasks.size());
            for (Supplier<T> task : tasks) {
                futures.add(executor.submit(() -> {
                    semaphore.acquire();
                    try {
                        return task.get();
                    } finally {
                        semaphore.release();
                    }
                }));
            }
            List<TaskResult<T>> results = new ArrayList<>(tasks.size());
            for (int i = 0; i < futures.size(); i++) {
                Duration timeout = timeouts != null && i < timeouts.size() ? timeouts.get(i) : null;
                results.add(awaitWithTimeout(futures.get(i), timeout));
            }
            return results;
        } finally {
            executor.shutdown();
        }
    }

    private <T> T await(Future<T> future) {
        try {
            return future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException ? (RuntimeException) cause : new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("parallel execution interrupted", e);
        }
    }

    /**
     * 带超时地等待单个 future，超时即 {@code cancel(true)} 中断虚拟线程，结果以 {@link TaskResult} 返回。
     */
    private <T> TaskResult<T> awaitWithTimeout(Future<T> future, Duration timeout) {
        if (timeout == null) {
            try {
                return TaskResult.ok(future.get());
            } catch (ExecutionException e) {
                return TaskResult.fail(unwrap(e));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return TaskResult.fail(e);
            }
        }
        try {
            return TaskResult.ok(future.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS));
        } catch (TimeoutException e) {
            // 中断虚拟线程，让阻塞的下游调用尽快退出，避免"超时了但线程还在跑"
            future.cancel(true);
            return TaskResult.timeout();
        } catch (ExecutionException e) {
            return TaskResult.fail(unwrap(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return TaskResult.fail(e);
        }
    }

    private static Throwable unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        return cause != null ? cause : e;
    }
}
