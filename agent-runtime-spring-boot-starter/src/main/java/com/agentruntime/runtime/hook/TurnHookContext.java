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

import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 一轮钩子共享的上下文。第一阶段放入的属性，第二阶段可以读到。
 *
 * <p>{@link #interrupt} 可从其他线程调用，先写入的原因和回复生效。
 * 门闩关闭后再次打断会被忽略。属性映射只在钩子方法所在的请求线程上读写；
 * 跨线程传递结果时，把 Future 放进属性，或直接调用 {@link #interrupt}。
 */
public class TurnHookContext {

    private final Object lock = new Object();

    private final CompletableFuture<Void> interruptSignal = new CompletableFuture<>();

    private final Map<String, Object> attributes = new HashMap<>();

    private String uid;

    private String sessionId;

    private String userText;

    /** 仅在 {@code beforePrompt} 之前由框架写入，钩子只读 */
    private SkillTurnPlan plan;

    private boolean interrupted;

    private boolean gateClosed;

    private String reasonCode;

    private String reply;

    public String getUid() {
        return uid;
    }

    void setUid(String uid) {
        this.uid = uid;
    }

    public String getSessionId() {
        return sessionId;
    }

    void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserText() {
        return userText;
    }

    void setUserText(String userText) {
        this.userText = userText;
    }

    /**
     * @return Skill 计划。{@code onPrepare} 期间为 null
     */
    public SkillTurnPlan getPlan() {
        return plan;
    }

    void setPlan(SkillTurnPlan plan) {
        this.plan = plan;
    }

    /**
     * 请求线程上的属性表，用于把第一阶段创建的 Future 交给第二阶段。
     *
     * @return 可变属性表
     */
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    /**
     * 提前结束本轮。原因码和回复都不能为空白。重复调用或门闩关闭后的调用被忽略。
     *
     * @param reasonCode 业务原因码
     * @param reply      返回给调用方的回复
     */
    public void interrupt(String reasonCode, String reply) {
        if (StringUtils.isBlank(reasonCode)) {
            throw new IllegalArgumentException("interrupt reasonCode must not be blank");
        }
        if (StringUtils.isBlank(reply)) {
            throw new IllegalArgumentException("interrupt reply must not be blank");
        }
        synchronized (lock) {
            if (gateClosed || interrupted) {
                return;
            }
            this.reasonCode = reasonCode;
            this.reply = reply;
            this.interrupted = true;
        }
        interruptSignal.complete(null);
    }

    public boolean isInterrupted() {
        synchronized (lock) {
            return interrupted;
        }
    }

    public String getReasonCode() {
        synchronized (lock) {
            return reasonCode;
        }
    }

    public String getReply() {
        synchronized (lock) {
            return reply;
        }
    }

    /**
     * 关闭打断入口，并判断是否可以继续组装 prompt。
     *
     * @return true 表示没有打断，可以继续
     */
    boolean seal() {
        synchronized (lock) {
            gateClosed = true;
            return !interrupted;
        }
    }

    CompletableFuture<Void> interruptSignal() {
        return interruptSignal;
    }
}
