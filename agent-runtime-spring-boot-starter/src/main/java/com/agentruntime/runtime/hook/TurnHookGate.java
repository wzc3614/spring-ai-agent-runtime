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

import com.agentruntime.capability.skill.SkillTurnPlan;
import com.agentruntime.core.springai.SpanContextHolder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * 轮次门闩。没有钩子时，Skill 准备留在调用线程。有钩子时，准备任务与 {@code onPrepare} 并行，
 * 先发生的打断不会等待准备结束。
 */
public class TurnHookGate {

    private static final Logger logger = LoggerFactory.getLogger(TurnHookGate.class);

    /**
     * 执行门闩。
     *
     * @param uid         用户 ID
     * @param sessionId   会话 ID
     * @param userText    用户输入
     * @param prepareTask Skill 准备
     * @param hooks       已注册钩子，按顺序执行
     * @param executor    准备任务执行器。没有钩子时不会使用
     * @return 打断结果或 Skill 计划
     */
    public TurnHookResult open(String uid, String sessionId, String userText,
                               Supplier<SkillTurnPlan> prepareTask, List<TurnHook> hooks, Executor executor) {
        if (CollectionUtils.isEmpty(hooks)) {
            return ready(prepareTask.get());
        }
        TurnHookContext context = newContext(uid, sessionId, userText);
        CompletableFuture<SkillTurnPlan> skillFuture = submit(prepareTask, executor);
        try {
            return decide(hooks, context, skillFuture);
        } finally {
            context.seal();
        }
    }

    private TurnHookResult decide(List<TurnHook> hooks, TurnHookContext context,
                                  CompletableFuture<SkillTurnPlan> skillFuture) {
        runOnPrepare(hooks, context);
        if (context.isInterrupted()) {
            return interrupted(context, TurnHookPhaseEnum.PREPARE);
        }
        SkillTurnPlan plan = awaitPrepare(skillFuture, context);
        if (context.isInterrupted()) {
            return interrupted(context, TurnHookPhaseEnum.PREPARE);
        }
        context.setPlan(plan);
        runBeforePrompt(hooks, context);
        if (!context.seal()) {
            return interrupted(context, TurnHookPhaseEnum.BEFORE_PROMPT);
        }
        return ready(plan);
    }

    private TurnHookContext newContext(String uid, String sessionId, String userText) {
        TurnHookContext context = new TurnHookContext();
        context.setUid(uid);
        context.setSessionId(sessionId);
        context.setUserText(userText);
        return context;
    }

    private CompletableFuture<SkillTurnPlan> submit(Supplier<SkillTurnPlan> prepareTask, Executor executor) {
        if (executor == null) {
            logger.warn("turn prepare executor missing, running on the caller thread");
            return CompletableFuture.completedFuture(prepareTask.get());
        }
        try {
            return CompletableFuture.supplyAsync(() -> invokePrepare(prepareTask), executor);
        } catch (RejectedExecutionException ex) {
            logger.warn("turn prepare executor rejected the task, running on the caller thread", ex);
            return CompletableFuture.completedFuture(prepareTask.get());
        }
    }

    /**
     * 在工作线程执行准备。清掉的是子线程继承来的 span 上下文，不影响请求线程。
     */
    private SkillTurnPlan invokePrepare(Supplier<SkillTurnPlan> prepareTask) {
        try {
            return prepareTask.get();
        } finally {
            SpanContextHolder.clear();
        }
    }

    private TurnHookResult ready(SkillTurnPlan plan) {
        TurnHookResult result = new TurnHookResult();
        result.setPlan(plan);
        return result;
    }

    private TurnHookResult interrupted(TurnHookContext context, TurnHookPhaseEnum phase) {
        TurnHookResult result = new TurnHookResult();
        result.setInterrupted(true);
        result.setPhase(phase);
        result.setReasonCode(context.getReasonCode());
        result.setReply(context.getReply());
        return result;
    }

    private void runOnPrepare(List<TurnHook> hooks, TurnHookContext context) {
        for (TurnHook hook : hooks) {
            if (context.isInterrupted()) {
                return;
            }
            hook.onPrepare(context);
        }
    }

    private void runBeforePrompt(List<TurnHook> hooks, TurnHookContext context) {
        for (TurnHook hook : hooks) {
            if (context.isInterrupted()) {
                return;
            }
            hook.beforePrompt(context);
        }
    }

    private SkillTurnPlan awaitPrepare(CompletableFuture<SkillTurnPlan> skillFuture, TurnHookContext context) {
        if (context.isInterrupted()) {
            return null;
        }
        try {
            CompletableFuture.anyOf(skillFuture, context.interruptSignal()).join();
        } catch (CompletionException ex) {
            if (context.isInterrupted()) {
                return null;
            }
            throw unwrap(ex);
        }
        if (context.isInterrupted()) {
            return null;
        }
        try {
            return skillFuture.join();
        } catch (CompletionException ex) {
            throw unwrap(ex);
        }
    }

    private RuntimeException unwrap(CompletionException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return exception;
    }
}
