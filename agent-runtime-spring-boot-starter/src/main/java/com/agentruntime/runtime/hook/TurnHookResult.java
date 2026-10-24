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

/**
 * 轮次门闩的结果。打断时携带回复；通过时携带 Skill 计划。
 */
public class TurnHookResult {

    private boolean interrupted;

    private String reply;

    private String reasonCode;

    private TurnHookPhaseEnum phase;

    private SkillTurnPlan plan;

    public boolean isInterrupted() {
        return interrupted;
    }

    void setInterrupted(boolean interrupted) {
        this.interrupted = interrupted;
    }

    public String getReply() {
        return reply;
    }

    void setReply(String reply) {
        this.reply = reply;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public TurnHookPhaseEnum getPhase() {
        return phase;
    }

    void setPhase(TurnHookPhaseEnum phase) {
        this.phase = phase;
    }

    public SkillTurnPlan getPlan() {
        return plan;
    }

    void setPlan(SkillTurnPlan plan) {
        this.plan = plan;
    }
}
