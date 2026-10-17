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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.springframework.util.CollectionUtils;

/**
 * 并行工具执行器：用虚拟线程并发执行多个独立任务，Semaphore 限并发，
 * 结果按入参顺序对齐返回。收编自 demo 的 BoundedAsyncTask 思路。
 */
public class ParallelToolExecutor {

    /**
     * 并发执行一批任务，按入参顺序返回结果。
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
}
