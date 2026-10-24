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
import com.agentruntime.api.capability.SkillRouteResult;
import com.agentruntime.api.capability.SkillSource;
import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.enums.SkillRouteSourceEnum;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.capability.skill.load.SkillInstructionBudget;
import com.agentruntime.capability.skill.load.SkillInstructionBudgeter;
import com.agentruntime.capability.skill.observe.SkillTurnObserver;
import com.agentruntime.capability.skill.prompt.SkillCatalogFormatter;
import com.agentruntime.capability.skill.prompt.SkillPromptComposer;
import com.agentruntime.capability.skill.registry.ClasspathSkillSource;
import com.agentruntime.capability.skill.registry.CompositeSkillRegistry;
import com.agentruntime.capability.skill.registry.SkillDescriptorValidator;
import com.agentruntime.capability.skill.registry.SkillMetadataHasher;
import com.agentruntime.capability.skill.route.CascadingSkillRouter;
import com.agentruntime.capability.skill.route.KeywordSkillMatcher;
import com.agentruntime.capability.skill.route.LlmSkillRouter;
import com.agentruntime.capability.skill.select.SkillToolSelector;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillPipelineTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final SkillDescriptorValidator validator = new SkillDescriptorValidator();

    @Test
    void rejectsIllegalSkillAndDanglingToolRef() {
        SkillDescriptor missingName = skill("order-query", "订单查询", "desc", List.of("query_user_info"));
        missingName.setName(" ");
        assertFalse(validator.isWellFormed(missingName));

        SkillDescriptor illegalId = skill("OrderQuery", "订单查询", "desc", List.of("query_user_info"));
        assertFalse(validator.isWellFormed(illegalId));

        SkillDescriptor ok = skill("order-query", "订单查询", "desc", List.of("query_user_info"));
        assertTrue(validator.isWellFormed(ok));
        assertFalse(validator.hasResolvableTools(ok, Set.of("other_tool")));
        assertTrue(validator.hasResolvableTools(ok, Set.of("query_user_info")));
    }

    @Test
    void keywordHitSkipsLlmAndNotForDoesNotExclude() {
        AtomicInteger llmCalls = new AtomicInteger();
        ChatModel model = prompt -> {
            llmCalls.incrementAndGet();
            return reply("{\"skillIds\":[]}");
        };
        CascadingSkillRouter router = new CascadingSkillRouter(new KeywordSkillMatcher(),
                new LlmSkillRouter(model, objectMapper, 1), 1);
        SkillDescriptor order = skill("order-query", "订单查询", "查订单", List.of("query_recent_orders"));
        order.setNotFor(List.of("取消订单"));
        order.setTriggers(List.of("订单"));

        SkillRouteResult hit = router.route("帮我查订单", List.of(order));
        assertEquals(SkillRouteSourceEnum.RULE, hit.getSource());
        assertEquals("order-query", hit.getSelected().get(0).getSkillId());
        assertEquals(0, llmCalls.get());

        SkillRouteResult negative = router.route("我要取消订单", List.of(order));
        assertEquals(SkillRouteSourceEnum.RULE, negative.getSource());
        assertEquals(0, llmCalls.get());
    }

    @Test
    void llmFailureLeavesSelectionEmptyAndMainRouteContinues() {
        ChatModel broken = prompt -> {
            throw new IllegalStateException("router down");
        };
        LlmSkillRouter llm = new LlmSkillRouter(broken, objectMapper, 1);
        SkillDescriptor order = skill("order-query", "订单查询", "查订单", List.of("query_recent_orders"));
        SkillRouteResult failed = llm.route("你好", List.of(order));
        assertTrue(failed.getSelected().isEmpty());
        assertEquals(SkillRouteSourceEnum.NONE, failed.getSource());

        ChatModel invalid = prompt -> reply("not-json");
        SkillRouteResult badJson = new LlmSkillRouter(invalid, objectMapper, 1).route("你好", List.of(order));
        assertTrue(badJson.getSelected().isEmpty());
        assertEquals(SkillRouteSourceEnum.NONE, badJson.getSource());

        ChatModel empty = prompt -> reply("{\"skillIds\":[],\"reason\":\"none\"}");
        SkillRouteResult none = new LlmSkillRouter(empty, objectMapper, 1).route("你好", List.of(order));
        assertTrue(none.getSelected().isEmpty());
        assertEquals(SkillRouteSourceEnum.LLM, none.getSource());
    }

    @Test
    void jdbcOverrideKeepsClasspathWhenDanglingAndDisableHidesIt() {
        SkillDescriptor classpath = skill("order-query", "订单查询", "classpath", List.of("query_user_info"));
        FakeSource classpathSource = new FakeSource("classpath", "c1", List.of(classpath));

        SkillDescriptor dangling = skill("order-query", "订单查询", "jdbc-dangling", List.of("missing_tool"));
        FakeSource jdbcDangling = new FakeSource("jdbc", "j1", List.of(dangling));
        CompositeSkillRegistry danglingRegistry = new CompositeSkillRegistry(
                List.of(classpathSource, jdbcDangling), validator);
        List<SkillDescriptor> kept = danglingRegistry.list(Set.of("query_user_info"));
        assertEquals(1, kept.size());
        assertEquals("classpath", kept.get(0).getDescription());

        SkillDescriptor disabled = skill("order-query", "订单查询", "jdbc-off", List.of("query_user_info"));
        disabled.setEnabled(false);
        FakeSource jdbcOff = new FakeSource("jdbc", "j2", List.of(disabled));
        CompositeSkillRegistry disabledRegistry = new CompositeSkillRegistry(
                List.of(classpathSource, jdbcOff), validator);
        assertTrue(disabledRegistry.list(Set.of("query_user_info")).isEmpty());

        SkillDescriptor override = skill("order-query", "订单查询", "jdbc-on", List.of("query_user_info"));
        FakeSource jdbcOn = new FakeSource("jdbc", "j3", List.of(override));
        CompositeSkillRegistry overrideRegistry = new CompositeSkillRegistry(
                List.of(new FakeSource("classpath", "c1", List.of(classpath)), jdbcOn), validator);
        List<SkillDescriptor> replaced = overrideRegistry.list(Set.of("query_user_info"));
        assertEquals("jdbc-on", replaced.get(0).getDescription());
    }

    @Test
    void unchangedRevisionDoesNotReloadMetadata() {
        SkillDescriptor skill = skill("order-query", "订单查询", "desc", List.of("query_user_info"));
        FakeSource source = new FakeSource("classpath", "same", List.of(skill));
        CompositeSkillRegistry registry = new CompositeSkillRegistry(List.of(source), validator);
        registry.list(Set.of("query_user_info"));
        registry.list(Set.of("query_user_info"));
        assertEquals(1, source.loads);
    }

    @Test
    void truncatesInstructionAndAppendsMarker() {
        SkillInstructionBudgeter budgeter = new SkillInstructionBudgeter(new CharTokenEstimator());
        String body = "a".repeat(100);
        SkillInstructionBudget budget = budgeter.apply("order-query", body, 40);
        assertTrue(budget.isTruncated());
        assertTrue(budget.getText().endsWith(SkillConstants.TRUNCATION_MARKER));
        assertTrue(budget.getTokens() <= 40);
        assertEquals(100, budget.getEstimatedTokens());
    }

    @Test
    void selectorKeepsAlwaysOnToolsAndGatesReferencedOnes() {
        ToolCallback orders = tool("query_recent_orders");
        ToolCallback badcase = tool("flaky_idempotent_lookup");
        SkillDescriptor skill = skill("order-query", "订单查询", "desc", List.of("query_recent_orders"));
        SkillToolSelector selector = new SkillToolSelector();

        List<ToolCallback> hit = selector.select(List.of(orders, badcase), List.of(skill), List.of(skill));
        assertEquals(List.of("query_recent_orders", "flaky_idempotent_lookup"), names(hit));

        List<ToolCallback> miss = selector.select(List.of(orders, badcase), List.of(skill), List.of());
        assertEquals(List.of("flaky_idempotent_lookup"), names(miss));
    }

    @Test
    void catalogOmitsToolsAndInstructionAndLoaderIsNotCached() {
        SkillDescriptor skill = skill("order-query", "订单查询", "查订单", List.of("query_recent_orders"));
        skill.setNotFor(List.of("支付"));
        String catalog = new SkillCatalogFormatter().format(List.of(skill));
        assertTrue(catalog.contains("订单查询"));
        assertTrue(catalog.contains("查订单"));
        assertTrue(catalog.contains("支付"));
        assertFalse(catalog.contains("query_recent_orders"));

        AtomicInteger loads = new AtomicInteger();
        SkillInstructionLoader loader = ref -> {
            loads.incrementAndGet();
            return "body-" + loads.get();
        };
        FakeSource source = new FakeSource("classpath", "r1", List.of(skill));
        DefaultSkillRuntime runtime = runtime(new CompositeSkillRegistry(List.of(source), validator),
                new CascadingSkillRouter(new KeywordSkillMatcher(), null, 1), loader, 2000);
        SkillTurnPlan first = runtime.prepare("订单查询", "system", List.of(tool("query_recent_orders"), tool("other")));
        SkillTurnPlan second = runtime.prepare("订单查询", "system", List.of(tool("query_recent_orders"), tool("other")));
        assertEquals(2, loads.get());
        assertFalse(first.isPassthrough());
        assertTrue(first.isActivated());
        assertTrue(first.getSystemText().contains("system"));
        assertTrue(first.getSystemText().contains("查订单"));
        assertEquals("body-1", first.getInstructionMessages().get(0).getText());
        assertEquals("body-2", second.getInstructionMessages().get(0).getText());
    }

    @Test
    void emptyRegistryPassesAllToolsThrough() {
        FakeSource source = new FakeSource("classpath", "empty", List.of());
        DefaultSkillRuntime runtime = runtime(new CompositeSkillRegistry(List.of(source), validator),
                new CascadingSkillRouter(new KeywordSkillMatcher(), null, 1), ref -> "x", 2000);
        List<ToolCallback> tools = List.of(tool("query_recent_orders"));
        SkillTurnPlan plan = runtime.prepare("hello", "system", tools);
        assertTrue(plan.isPassthrough());
        assertEquals(tools, plan.getTools());
    }

    @Test
    void classpathSourceReadsMetadataWithoutCachingInstructionFile() {
        SkillMetadataHasher hasher = new SkillMetadataHasher(objectMapper);
        ClasspathSkillSource source = new ClasspathSkillSource("classpath*:skills/*/skill.json", hasher, objectMapper);
        String first = source.revision();
        String second = source.revision();
        assertEquals(first, second);
        List<SkillDescriptor> skills = source.loadMetadata();
        assertEquals(1, skills.size());
        assertEquals("sample-skill", skills.get(0).getSkillId());
        assertEquals("classpath:skills/sample-skill/INSTRUCTION.md", skills.get(0).getInstructionRef());
        assertFalse(skills.get(0).getContentHash().isBlank());
    }

    @Test
    void hashChangesWhenDescriptionChanges() {
        SkillMetadataHasher hasher = new SkillMetadataHasher(objectMapper);
        SkillDescriptor skill = skill("order-query", "订单查询", "one", List.of("query_user_info"));
        String before = hasher.hash(skill);
        skill.setDescription("two");
        assertFalse(before.equals(hasher.hash(skill)));
    }

    private DefaultSkillRuntime runtime(CompositeSkillRegistry registry, CascadingSkillRouter router,
                                        SkillInstructionLoader loader, int maxTokens) {
        return new DefaultSkillRuntime(registry, router, loader, new SkillInstructionBudgeter(new CharTokenEstimator()),
                new SkillCatalogFormatter(), new SkillPromptComposer(), new SkillToolSelector(),
                new SkillTurnObserver(new NoopRecorder(), objectMapper), new CharTokenEstimator(), maxTokens, 1);
    }

    private static SkillDescriptor skill(String id, String name, String description, List<String> toolRefs) {
        SkillDescriptor descriptor = new SkillDescriptor();
        descriptor.setSkillId(id);
        descriptor.setName(name);
        descriptor.setDescription(description);
        descriptor.setToolRefs(new ArrayList<>(toolRefs));
        descriptor.setInstructionRef("classpath:skills/" + id + "/INSTRUCTION.md");
        descriptor.setEnabled(true);
        return descriptor;
    }

    private static ToolCallback tool(String name) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "ok";
            }
        };
    }

    private static List<String> names(List<ToolCallback> tools) {
        List<String> names = new ArrayList<>();
        for (ToolCallback tool : tools) {
            names.add(tool.getToolDefinition().name());
        }
        return names;
    }

    private static ChatResponse reply(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static final class FakeSource implements SkillSource {

        private final String name;

        private final String revision;

        private final List<SkillDescriptor> metadata;

        private int loads;

        private FakeSource(String name, String revision, List<SkillDescriptor> metadata) {
            this.name = name;
            this.revision = revision;
            this.metadata = metadata;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String revision() {
            return revision;
        }

        @Override
        public List<SkillDescriptor> loadMetadata() {
            loads++;
            return metadata;
        }
    }

    private static final class CharTokenEstimator implements TokenEstimator {

        @Override
        public int estimate(String text) {
            return text == null ? 0 : text.length();
        }

        @Override
        public int estimateJson(String json) {
            return estimate(json);
        }

        @Override
        public int estimateMessages(List<AgentMessage> messages) {
            return 0;
        }
    }

    private static final class NoopRecorder implements com.agentruntime.api.observability.ObservabilityRecorder {

        @Override
        public com.agentruntime.api.domain.Span startSpan(
                com.agentruntime.api.enums.SpanTypeEnum type, String parentSpanId, String sessionId) {
            com.agentruntime.api.domain.Span span = new com.agentruntime.api.domain.Span();
            span.setSpanId("span");
            span.setParentSpanId(parentSpanId);
            span.setSessionId(sessionId);
            span.setSpanType(type);
            return span;
        }

        @Override
        public void endSpan(String spanId) {
        }

        @Override
        public void recordEvent(String spanId, com.agentruntime.api.enums.EventTypeEnum type, String payload) {
        }
    }
}
