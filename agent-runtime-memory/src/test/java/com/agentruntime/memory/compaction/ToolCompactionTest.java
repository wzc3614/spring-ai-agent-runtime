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
package com.agentruntime.memory.compaction;

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.enums.MessageRoleEnum;
import com.agentruntime.api.enums.ToolCompactionStrategyEnum;
import com.agentruntime.api.memory.ToolCompactionConstants;
import com.agentruntime.api.memory.ToolCompactionPolicyRegistry;
import com.agentruntime.api.memory.ToolCompactionRule;
import com.agentruntime.memory.chat.MessageConverter;
import com.agentruntime.memory.write.WriteTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCompactionTest {

    private static final String QUERY_TOOL = "query_user_info";

    private static final String SEARCH_TOOL = "search_docs";

    private static final String RETAIN_TOOL = "create_order";

    private ObjectMapper objectMapper;

    private MessageConverter converter;

    private ToolCompactionPolicyRegistry registry;

    private ToolResultRenderer renderer;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        converter = new MessageConverter(objectMapper);
        registry = ToolCompactionPolicyRegistry.withDefaults();
        registry.register(ToolCompactionRule.query(QUERY_TOOL, ToolCompactionConstants.DEFAULT_QUERY_EXPIRE_TURNS));
        registry.register(ToolCompactionRule.summarize(SEARCH_TOOL, 40));
        registry.register(ToolCompactionRule.retain(RETAIN_TOOL));
        renderer = new ToolResultRenderer(registry, objectMapper, List.of());
    }

    @Test
    void queryExpiresWhenNextUserTurnStarts() {
        List<AgentMessage> history = List.of(user(1L), tool(2L, "c1", QUERY_TOOL, "{\"name\":\"ada\"}"));

        String incoming = renderer.render(history, true).get(1).getContent();
        String storedTurn = renderer.render(history, false).get(1).getContent();

        assertTrue(incoming.contains("query result expired"));
        assertTrue(incoming.contains(QUERY_TOOL));
        assertTrue(storedTurn.contains("ada"));
        assertFalse(storedTurn.contains("query result expired"));
    }

    @Test
    void querySurvivesOneExtraTurnWhenExpireIsTwo() {
        registry.register(ToolCompactionRule.query(QUERY_TOOL, 2));
        List<AgentMessage> history = List.of(user(1L), tool(2L, "c1", QUERY_TOOL, "fresh"), user(3L));

        String stillVisible = renderer.render(history, false).get(1).getContent();
        String expired = renderer.render(history, true).get(1).getContent();

        assertTrue(stillVisible.contains("fresh"));
        assertTrue(expired.contains("query result expired"));
    }

    @Test
    void retainKeepsOriginalAndSearchBecomesSummary() {
        String raw = "[\"alpha\",\"beta\",\"gamma-result-that-is-quite-long\"]";
        List<AgentMessage> history = List.of(
                user(1L),
                tool(2L, "c-retain", RETAIN_TOOL, raw),
                tool(3L, "c-search", SEARCH_TOOL, raw));

        List<AgentMessage> rendered = renderer.render(history, true);

        assertTrue(rendered.get(1).getContent().contains("gamma-result-that-is-quite-long"));
        String summary = rendered.get(2).getContent();
        assertTrue(summary.contains("count"));
        assertFalse(summary.contains("gamma-result-that-is-quite-long"));
    }

    @Test
    void unregisteredToolExpiresAsQuery() {
        List<AgentMessage> history = List.of(user(1L), tool(2L, "c9", "unknown_tool", "keep-me"));

        String content = renderer.render(history, true).get(1).getContent();

        assertTrue(content.contains("query result expired"));
        assertTrue(content.contains("unknown_tool"));
        assertFalse(content.contains("keep-me"));
    }

    @Test
    void storedQueryClearsPayloadAfterExpiry() {
        AgentMessage message = tool(2L, "c1", QUERY_TOOL, "secret");
        ToolViewUpdate update = renderer.renderStored(message, 1);

        assertTrue(update.isChanged());
        assertTrue(update.isCompacted());
        assertTrue(update.getContent().contains("query result expired"));
        assertFalse(update.getPayload().contains("secret"));
    }

    @Test
    void preparerClassifiesMixedToolsAsRetainAndTruncatesPayload() {
        ToolMessagePreparer preparer = new ToolMessagePreparer(converter, registry, new ToolPayloadCodec(objectMapper));
        AssistantMessage assistant = new AssistantMessage("", Map.of(), List.of(
                new AssistantMessage.ToolCall("1", "function", RETAIN_TOOL, "{}"),
                new AssistantMessage.ToolCall("2", "function", QUERY_TOOL, "{}")));
        WriteTask assistantTask = new WriteTask();
        preparer.apply(assistant, assistantTask);
        assertEquals(ToolCompactionStrategyEnum.RETAIN.getCode(), assistantTask.getCompactionClass());

        String oversized = "x".repeat(ToolCompactionConstants.MAX_PAYLOAD_CHARS + 8);
        ToolResponseMessage toolMessage = new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse("c1", QUERY_TOOL, oversized)));
        WriteTask toolTask = new WriteTask();
        preparer.apply(toolMessage, toolTask);

        assertEquals(ToolCompactionStrategyEnum.QUERY.getCode(), toolTask.getCompactionClass());
        assertTrue(toolTask.getPayload().contains("\"truncated\":true"));
        assertFalse(toolTask.getContent().contains(oversized));
    }

    private AgentMessage user(long seq) {
        AgentMessage message = new AgentMessage();
        message.setMessageId("u-" + seq);
        message.setSeq(seq);
        message.setRole(MessageRoleEnum.USER);
        message.setContent("{\"type\":\"user\",\"content\":\"hello\"}");
        message.setCreatedAt(Instant.EPOCH);
        return message;
    }

    private AgentMessage tool(long seq, String callId, String name, String data) {
        ToolResponseMessage response = new ToolResponseMessage(List.of(
                new ToolResponseMessage.ToolResponse(callId, name, data)));
        AgentMessage message = new AgentMessage();
        message.setMessageId("t-" + seq);
        message.setSeq(seq);
        message.setRole(MessageRoleEnum.TOOL);
        message.setContent(converter.encode(response));
        message.setCreatedAt(Instant.EPOCH);
        return message;
    }
}
