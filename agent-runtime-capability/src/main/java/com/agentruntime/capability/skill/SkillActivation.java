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
package com.agentruntime.capability.skill;

import com.agentruntime.api.domain.Span;
import com.agentruntime.capability.skill.trace.SkillToolTrace;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一轮 Skill 激活的运行时状态。未命中时没有 SKILL span。
 */
public class SkillActivation {

    private SkillTurnPlan plan;

    private Span skillSpan;

    private Instant startedAt;

    /** 主模型调用应挂到的父 span。命中时为 SKILL，否则为 null，由编排器继续用 TURN */
    private String parentSpanIdForCall;

    private List<SkillToolTrace> traceBuffer;

    private final AtomicBoolean finished = new AtomicBoolean(false);

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * @return 本次是否第一次结束
     */
    public boolean markFinished() {
        return finished.compareAndSet(false, true);
    }

    /**
     * @return 本次是否第一次关闭
     */
    public boolean markClosed() {
        return closed.compareAndSet(false, true);
    }

    public SkillTurnPlan getPlan() {
        return plan;
    }

    public void setPlan(SkillTurnPlan plan) {
        this.plan = plan;
    }

    public Span getSkillSpan() {
        return skillSpan;
    }

    public void setSkillSpan(Span skillSpan) {
        this.skillSpan = skillSpan;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public String getParentSpanIdForCall() {
        return parentSpanIdForCall;
    }

    public void setParentSpanIdForCall(String parentSpanIdForCall) {
        this.parentSpanIdForCall = parentSpanIdForCall;
    }

    public List<SkillToolTrace> getTraceBuffer() {
        return traceBuffer;
    }

    public void setTraceBuffer(List<SkillToolTrace> traceBuffer) {
        this.traceBuffer = traceBuffer;
    }
}
