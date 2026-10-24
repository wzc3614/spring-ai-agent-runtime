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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Skill 准备与轮次钩子并行时使用的虚拟线程执行器。
 * 容器关闭时停止接收新任务，并等待已提交任务结束。
 */
public class TurnPrepareExecutor implements DisposableBean {

    private static final Logger logger = LoggerFactory.getLogger(TurnPrepareExecutor.class);

    /** 停机最长等待时间（秒） */
    private static final long SHUTDOWN_AWAIT_SECONDS = 15L;

    private final ExecutorService executor;

    public TurnPrepareExecutor() {
        this.executor = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("agent-runtime-turn-", 0L).factory());
    }

    /**
     * @return 提交准备任务的执行器
     */
    public ExecutorService getExecutor() {
        return executor;
    }

    @Override
    public void destroy() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                logger.warn("turn prepare executor did not terminate in {} seconds", SHUTDOWN_AWAIT_SECONDS);
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
            logger.warn("turn prepare executor shutdown interrupted");
        }
    }
}
