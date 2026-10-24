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
package com.agentruntime.api.memory;

import com.agentruntime.api.enums.ToolCompactionStrategyEnum;

import org.apache.commons.lang3.StringUtils;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具压缩策略注册表的默认实现。默认策略：查询类，下一条用户消息起过期。
 * 注册的策略按 toolName 覆盖默认策略。放在 api 模块以便
 * {@link ToolCompactionPolicyRegistry#withDefaults()} 引用。
 */
public class DefaultToolCompactionPolicyRegistry implements ToolCompactionPolicyRegistry {

    private final Map<String, ToolCompactionRule> rules = new ConcurrentHashMap<>();

    @Override
    public ToolCompactionRule getRule(String toolName) {
        if (StringUtils.isEmpty(toolName)) {
            return defaultRule();
        }
        return rules.getOrDefault(toolName, defaultRule());
    }

    @Override
    public void register(ToolCompactionRule rule) {
        rules.put(rule.getToolName(), rule);
    }

    @Override
    public ToolCompactionRule defaultRule() {
        ToolCompactionRule rule = new ToolCompactionRule();
        rule.setToolName(ToolCompactionConstants.DEFAULT_RULE_NAME);
        rule.setStrategy(ToolCompactionStrategyEnum.QUERY);
        rule.setExpireAfterTurns(ToolCompactionConstants.DEFAULT_QUERY_EXPIRE_TURNS);
        return rule;
    }
}
