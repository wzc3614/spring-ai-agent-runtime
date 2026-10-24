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
package com.agentruntime.core.safety.lifecycle;

import com.agentruntime.api.lifecycle.AgentLifecycleEvent;
import com.agentruntime.api.lifecycle.AgentLifecycleListener;
import com.agentruntime.api.lifecycle.AgentLifecycleTypeEnum;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * 会话生命周期清理器。终结事件时按注册顺序执行各子系统的清理任务，框架替业务扛脏活。
 */
public class SessionLifecycleCleaner implements AgentLifecycleListener {

    private final List<Consumer<AgentLifecycleEvent>> cleanupTasks = new CopyOnWriteArrayList<>();

    /**
     * 注册一个清理任务。
     *
     * @param task 清理任务，接收生命周期事件
     */
    public void registerCleanup(Consumer<AgentLifecycleEvent> task) {
        cleanupTasks.add(task);
    }

    @Override
    public void onEvent(AgentLifecycleEvent event) {
        if (!isTerminal(event.getType())) {
            return;
        }
        for (Consumer<AgentLifecycleEvent> task : cleanupTasks) {
            task.accept(event);
        }
    }

    private boolean isTerminal(AgentLifecycleTypeEnum type) {
        return type == AgentLifecycleTypeEnum.ENDED
                || type == AgentLifecycleTypeEnum.CANCELLED
                || type == AgentLifecycleTypeEnum.TIMED_OUT;
    }
}
