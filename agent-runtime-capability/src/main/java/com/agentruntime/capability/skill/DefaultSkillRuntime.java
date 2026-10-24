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

import com.agentruntime.api.capability.SkillDescriptor;
import com.agentruntime.api.capability.SkillInstructionLoader;
import com.agentruntime.api.capability.SkillRegistry;
import com.agentruntime.api.capability.SkillRouteResult;
import com.agentruntime.api.capability.SkillRouter;
import com.agentruntime.api.enums.SkillDeactivateReasonEnum;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.capability.skill.load.SkillInstructionBudget;
import com.agentruntime.capability.skill.load.SkillInstructionBudgeter;
import com.agentruntime.capability.skill.observe.SkillTurnObserver;
import com.agentruntime.capability.skill.prompt.SkillCatalogFormatter;
import com.agentruntime.capability.skill.prompt.SkillPromptComposer;
import com.agentruntime.capability.skill.select.SkillToolSelector;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Skill 管道默认实现。目录文本按 revision 和可见 skillId 缓存；指令正文每次激活重新加载。
 */
public class DefaultSkillRuntime implements SkillRuntime {

    private static final Logger logger = LoggerFactory.getLogger(DefaultSkillRuntime.class);

    private final SkillRegistry registry;

    private final SkillRouter router;

    private final SkillInstructionLoader instructionLoader;

    private final SkillInstructionBudgeter budgeter;

    private final SkillCatalogFormatter catalogFormatter;

    private final SkillPromptComposer promptComposer;

    private final SkillToolSelector toolSelector;

    private final SkillTurnObserver observer;

    private final TokenEstimator tokenEstimator;

    private final int instructionMaxTokens;

    private final int topN;

    private final ConcurrentHashMap<String, String> catalogCache = new ConcurrentHashMap<>();

    private final Object catalogLock = new Object();

    private String catalogRevision = "";

    public DefaultSkillRuntime(SkillRegistry registry, SkillRouter router, SkillInstructionLoader instructionLoader,
                               SkillInstructionBudgeter budgeter, SkillCatalogFormatter catalogFormatter,
                               SkillPromptComposer promptComposer, SkillToolSelector toolSelector,
                               SkillTurnObserver observer, TokenEstimator tokenEstimator,
                               int instructionMaxTokens, int topN) {
        this.registry = registry;
        this.router = router;
        this.instructionLoader = instructionLoader;
        this.budgeter = budgeter;
        this.catalogFormatter = catalogFormatter;
        this.promptComposer = promptComposer;
        this.toolSelector = toolSelector;
        this.observer = observer;
        this.tokenEstimator = tokenEstimator;
        this.instructionMaxTokens = instructionMaxTokens < 1
                ? SkillConstants.DEFAULT_INSTRUCTION_MAX_TOKENS : instructionMaxTokens;
        this.topN = topN < 1 ? SkillConstants.DEFAULT_TOP_N : topN;
    }

    @Override
    public SkillTurnPlan prepare(String userText, String defaultSystem, List<ToolCallback> allTools) {
        try {
            return prepareUnchecked(userText, defaultSystem, allTools);
        } catch (RuntimeException ex) {
            logger.error("skill prepare failed", ex);
            return SkillTurnPlan.passthrough(allTools);
        }
    }

    @Override
    public SkillActivation begin(String turnSpanId, String sessionId, SkillTurnPlan plan) {
        return observer.begin(turnSpanId, sessionId, plan);
    }

    @Override
    public void finish(SkillActivation activation, SkillDeactivateReasonEnum reason, boolean success) {
        observer.finish(activation, reason, success);
    }

    @Override
    public void close(SkillActivation activation) {
        observer.close(activation);
    }

    private SkillTurnPlan prepareUnchecked(String userText, String defaultSystem, List<ToolCallback> allTools) {
        Set<String> toolNames = toolNames(allTools);
        List<SkillDescriptor> skills = registry.list(toolNames);
        if (CollectionUtils.isEmpty(skills)) {
            return SkillTurnPlan.passthrough(allTools);
        }
        SkillRouteResult route = router.route(userText, skills);
        List<SkillDescriptor> selected = route.getSelected() == null ? List.of() : route.getSelected();
        SkillTurnPlan plan = new SkillTurnPlan();
        plan.setPassthrough(false);
        plan.setSystemText(promptComposer.composeSystem(defaultSystem, catalog(skills)));
        plan.setRoute(route);
        plan.setTools(toolSelector.select(allTools, skills, selected));
        if (CollectionUtils.isEmpty(selected)) {
            plan.setActivated(false);
            return plan;
        }
        plan.setActivated(true);
        applyInstructions(plan, selected);
        plan.setActivatedToolNames(toolSelector.activatedToolNames(plan.getTools(), selected));
        plan.setActivationToolSchemaTokens(schemaTokens(plan.getTools(), plan.getActivatedToolNames()));
        return plan;
    }

    private void applyInstructions(SkillTurnPlan plan, List<SkillDescriptor> selected) {
        int remaining = instructionMaxTokens * topN;
        int instructionTokens = 0;
        List<String> texts = new ArrayList<>();
        List<SkillBudgetHit> hits = new ArrayList<>();
        for (SkillDescriptor skill : selected) {
            String body = instructionLoader.load(skill.getInstructionRef());
            if (StringUtils.isEmpty(body)) {
                logger.warn("skill instruction empty, skillId={}, ref={}", skill.getSkillId(), skill.getInstructionRef());
            }
            int budget = Math.min(instructionMaxTokens, Math.max(remaining, 0));
            SkillInstructionBudget applied = budgeter.apply(skill.getSkillId(), body, budget);
            if (applied.isTruncated()) {
                SkillBudgetHit hit = new SkillBudgetHit();
                hit.setSkillId(skill.getSkillId());
                hit.setEstimatedTokens(applied.getEstimatedTokens());
                hit.setBudget(budget);
                hits.add(hit);
            }
            instructionTokens += applied.getTokens();
            remaining -= applied.getTokens();
            texts.add(applied.getText());
        }
        List<Message> messages = promptComposer.instructionMessages(texts);
        plan.setInstructionMessages(messages);
        plan.setInstructionTokens(instructionTokens);
        plan.setBudgetHits(hits);
    }

    private String catalog(List<SkillDescriptor> skills) {
        String revision = registry.revision();
        String key = revision + "|" + skillIds(skills);
        synchronized (catalogLock) {
            if (!revision.equals(catalogRevision)) {
                catalogCache.clear();
                catalogRevision = revision;
            }
            return catalogCache.computeIfAbsent(key, ignored -> catalogFormatter.format(skills));
        }
    }

    private String skillIds(List<SkillDescriptor> skills) {
        StringBuilder builder = new StringBuilder();
        for (SkillDescriptor skill : skills) {
            if (!builder.isEmpty()) {
                builder.append(',');
            }
            builder.append(skill.getSkillId());
        }
        return builder.toString();
    }

    private Set<String> toolNames(List<ToolCallback> tools) {
        Set<String> names = new HashSet<>();
        if (CollectionUtils.isEmpty(tools)) {
            return names;
        }
        for (ToolCallback tool : tools) {
            names.add(tool.getToolDefinition().name());
        }
        return names;
    }

    private int schemaTokens(List<ToolCallback> tools, List<String> skillToolNames) {
        if (CollectionUtils.isEmpty(tools) || CollectionUtils.isEmpty(skillToolNames)) {
            return 0;
        }
        Set<String> wanted = new HashSet<>(skillToolNames);
        int total = 0;
        for (ToolCallback tool : tools) {
            ToolDefinition definition = tool.getToolDefinition();
            if (!wanted.contains(definition.name())) {
                continue;
            }
            total += tokenEstimator.estimate(definition.name());
            total += tokenEstimator.estimate(definition.description());
            total += tokenEstimator.estimateJson(definition.inputSchema());
        }
        return total;
    }
}
