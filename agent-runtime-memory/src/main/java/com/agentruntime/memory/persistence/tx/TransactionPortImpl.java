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
package com.agentruntime.memory.persistence.tx;

import com.agentruntime.api.spi.TransactionPort;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.Callable;

/**
 * {@link TransactionPort} 的默认实现，基于 Spring {@link TransactionTemplate}。
 * 业务逻辑抛异常即标记回滚；受检异常包装为运行时异常再抛。
 */
public class TransactionPortImpl implements TransactionPort {

    private final TransactionTemplate transactionTemplate;

    public TransactionPortImpl(PlatformTransactionManager transactionManager) {
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T executeInTransaction(Callable<T> action) {
        return transactionTemplate.execute(status -> {
            try {
                return action.call();
            } catch (Exception e) {
                status.setRollbackOnly();
                throw toRuntimeException(e);
            }
        });
    }

    @Override
    public void executeInTransaction(Runnable action) {
        transactionTemplate.executeWithoutResult(status -> {
            try {
                action.run();
            } catch (Exception e) {
                status.setRollbackOnly();
                throw toRuntimeException(e);
            }
        });
    }

    private static RuntimeException toRuntimeException(Exception e) {
        return e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
    }
}
