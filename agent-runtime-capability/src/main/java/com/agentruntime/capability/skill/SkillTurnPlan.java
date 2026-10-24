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

import com.agentruntime.api.capability.SkillRouteResult;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮调用在进入 ChatClient 之前准备好的 Skill 计划。
 * {@code passthrough=true} 表示注册表为空，编排器保持原有系统提示和全部工具。
 */
public class SkillTurnPlan {

    private boolean passthrough;

    /** 含目录的系统提示。passthrough 时为 null，表示沿用 ChatClient 默认系统提示 */
    private String systemText;

    private List<Message> instructionMessages = new ArrayList<>();

    private List<ToolCallback> tools = new ArrayList<>();

    private SkillRouteResult route;

    private boolean activated;

    private int instructionTokens;

    /** 命中 Skill 所放行工具的 schema token，计入激活消耗 */
    private int activationToolSchemaTokens;

    private List<String> activatedToolNames = new ArrayList<>();

    private List<SkillBudgetHit> budgetHits = new ArrayList<>();

    /**
     * 不做 Skill 处理的计划。
     *
     * @param tools 全部工具
     * @return 透传计划
     */
    public static SkillTurnPlan passthrough(List<ToolCallback> tools) {
        SkillTurnPlan plan = new SkillTurnPlan();
        plan.setPassthrough(true);
        plan.setTools(tools == null ? List.of() : tools);
        return plan;
    }

    public boolean isPassthrough() {
        return passthrough;
    }

    public void setPassthrough(boolean passthrough) {
        this.passthrough = passthrough;
    }

    public String getSystemText() {
        return systemText;
    }

    public void setSystemText(String systemText) {
        this.systemText = systemText;
    }

    public List<Message> getInstructionMessages() {
        return instructionMessages;
    }

    public void setInstructionMessages(List<Message> instructionMessages) {
        this.instructionMessages = instructionMessages;
    }

    public List<ToolCallback> getTools() {
        return tools;
    }

    public void setTools(List<ToolCallback> tools) {
        this.tools = tools;
    }

    public SkillRouteResult getRoute() {
        return route;
    }

    public void setRoute(SkillRouteResult route) {
        this.route = route;
    }

    public boolean isActivated() {
        return activated;
    }

    public void setActivated(boolean activated) {
        this.activated = activated;
    }

    public int getInstructionTokens() {
        return instructionTokens;
    }

    public void setInstructionTokens(int instructionTokens) {
        this.instructionTokens = instructionTokens;
    }

    public int getActivationToolSchemaTokens() {
        return activationToolSchemaTokens;
    }

    public void setActivationToolSchemaTokens(int activationToolSchemaTokens) {
        this.activationToolSchemaTokens = activationToolSchemaTokens;
    }

    public List<String> getActivatedToolNames() {
        return activatedToolNames;
    }

    public void setActivatedToolNames(List<String> activatedToolNames) {
        this.activatedToolNames = activatedToolNames;
    }

    public List<SkillBudgetHit> getBudgetHits() {
        return budgetHits;
    }

    public void setBudgetHits(List<SkillBudgetHit> budgetHits) {
        this.budgetHits = budgetHits;
    }
}
