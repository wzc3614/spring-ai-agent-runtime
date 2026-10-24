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
package com.agentruntime.capability.skill.prompt;

import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 组装本轮系统提示和独立的指令消息。指令使用 SystemMessage，避免被记忆 Advisor 当成用户消息落库。
 */
public class SkillPromptComposer {

    /**
     * 把目录接在原系统提示之后，仍是一条系统消息。
     *
     * @param defaultSystem 构建 Agent 时的系统提示
     * @param catalog       目录文本
     * @return 本轮系统提示
     */
    public String composeSystem(String defaultSystem, String catalog) {
        if (StringUtils.isBlank(catalog)) {
            return defaultSystem;
        }
        if (StringUtils.isBlank(defaultSystem)) {
            return catalog;
        }
        return defaultSystem + "\n\n" + catalog;
    }

    /**
     * 每段非空指令一条系统消息，顺序与路由结果一致。
     *
     * @param instructions 已按预算截断的正文
     * @return 指令消息
     */
    public List<Message> instructionMessages(List<String> instructions) {
        List<Message> messages = new ArrayList<>();
        if (CollectionUtils.isEmpty(instructions)) {
            return messages;
        }
        for (String instruction : instructions) {
            if (StringUtils.isNotBlank(instruction)) {
                messages.add(new SystemMessage(instruction));
            }
        }
        return messages;
    }
}
