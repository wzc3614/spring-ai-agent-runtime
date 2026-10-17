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
package com.agentruntime.api.spi;

import java.util.concurrent.Callable;

/**
 * 事务端口 SPI。用于圈定跨多个仓储的原子写块，由 memory 模块基于 Spring
 * {@code TransactionTemplate} 提供默认实现。抛异常即回滚。
 */
public interface TransactionPort {

    /**
     * 在事务中执行有返回值的逻辑。
     *
     * @param action 业务逻辑
     * @param <T>     返回类型
     * @return 执行结果
     */
    <T> T executeInTransaction(Callable<T> action);

    /**
     * 在事务中执行无返回值的逻辑。
     *
     * @param action 业务逻辑
     */
    void executeInTransaction(Runnable action);
}
