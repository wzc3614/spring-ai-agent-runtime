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

import com.agentruntime.api.enums.SkillDeactivateReasonEnum;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * Skill 管道对编排器暴露的入口。注册表为空时 {@link #prepare} 返回透传计划。
 */
public interface SkillRuntime {

    /**
     * 路由、加载指令并裁剪工具。发生在工具循环之前。
     *
     * @param userText      用户输入
     * @param defaultSystem Agent 的默认系统提示
     * @param allTools      已包装的全部工具
     * @return 本轮计划
     */
    SkillTurnPlan prepare(String userText, String defaultSystem, List<ToolCallback> allTools);

    /**
     * 打开 SKILL span 并写下路由 / 激活事件。
     *
     * @param turnSpanId TURN span
     * @param sessionId  会话 ID
     * @param plan       本轮计划
     * @return 激活状态；透传时为 null
     */
    SkillActivation begin(String turnSpanId, String sessionId, SkillTurnPlan plan);

    /**
     * 写下执行和退出事件。
     *
     * @param activation 激活状态
     * @param reason     退出原因
     * @param success    主调用是否成功
     */
    void finish(SkillActivation activation, SkillDeactivateReasonEnum reason, boolean success);

    /**
     * 关闭 SKILL span。
     *
     * @param activation 激活状态
     */
    void close(SkillActivation activation);
}
