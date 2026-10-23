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
package com.agentruntime.memory.compaction;

import org.springframework.beans.factory.DisposableBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 摘要压缩异步执行器：基于虚拟线程的每任务一线程池，保证压缩任务不阻塞主流程。
 *
 * <p>虚拟线程由请求线程创建，{@code InheritableThreadLocal}（如 {@code MemoryBudgetContextHolder}）
 * 的值在虚拟线程启动时快照继承，使异步压缩任务可读到当前轮的 fixedOverhead。
 * 实现 {@link DisposableBean} 在容器关闭时优雅停机。
 */
public class SummaryExecutor implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(SummaryExecutor.class);

    /** 停机最长等待时间（秒） */
    private static final long SHUTDOWN_AWAIT_SECONDS = 15L;

    private final ExecutorService executor;

    public SummaryExecutor() {
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("summary-compactor-", 0L).factory());
    }

    /**
     * 提交一个压缩任务（fire-and-forget）。
     *
     * @param task 任务
     */
    public void submit(Runnable task) {
        executor.execute(task);
    }

    @Override
    public void destroy() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("summary executor did not terminate in {}s, forcing shutdown", SHUTDOWN_AWAIT_SECONDS);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
