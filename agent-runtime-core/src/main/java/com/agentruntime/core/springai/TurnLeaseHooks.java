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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 模型循环步边界上的租约续期。没有轮次执行时钩子为空，调用无效果。
 */
public final class TurnLeaseHooks {

    private static final Logger log = LoggerFactory.getLogger(TurnLeaseHooks.class);

    private static final InheritableThreadLocal<Runnable> HOOK = new InheritableThreadLocal<>();

    private TurnLeaseHooks() {
    }

    /**
     * @param hook 续约动作，null 表示清除
     */
    public static void set(Runnable hook) {
        if (hook == null) {
            HOOK.remove();
            return;
        }
        HOOK.set(hook);
    }

    /**
     * 执行当前线程上的续约钩子。钩子失败只记日志，不打断模型循环。
     */
    public static void renew() {
        Runnable hook = HOOK.get();
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (RuntimeException ex) {
            log.warn("turn lease renew hook failed");
        }
    }

    /**
     * 清除当前线程上的钩子。
     */
    public static void clear() {
        HOOK.remove();
    }
}
