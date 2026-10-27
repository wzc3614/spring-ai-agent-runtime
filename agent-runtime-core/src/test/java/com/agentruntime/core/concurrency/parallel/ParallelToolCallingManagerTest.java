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
package com.agentruntime.core.concurrency.parallel;

import com.agentruntime.core.tool.ToolGovernanceDefaults;
import com.agentruntime.core.tool.DefaultToolMetaRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 带 ToolContext 参数的工具在调用方没有预置上下文时也要能执行，并读到本次调用历史。
 */
class ParallelToolCallingManagerTest {

    private static final String TOOL_NAME = "read_context";

    private static final String USER_TEXT = "check the order";

    private static final String CALL_ID = "call-1";

    private static final String TOOL_CALL_TYPE = "function";

    private static final String ARGUMENTS = "{\"note\":\"ping\"}";

    private static final String RESULT = "ok";

    private static final String TRACE_KEY = "traceId";

    private static final String TRACE_VALUE = "trace-1";

    private final ParallelToolCallingManager manager = ParallelToolCallingManager.builder()
            .toolMetaRegistry(new DefaultToolMetaRegistry())
            .governanceDefaults(new ToolGovernanceDefaults())
            .build();

    @Test
    void toolContextIsPresentWhenCallerDidNotPresetIt() {
        ContextTool tool = new ContextTool();
        DefaultToolCallingChatOptions options = new DefaultToolCallingChatOptions();

        ToolExecutionResult result = execute(tool, options);

        assertEquals(jsonText(RESULT), responseText(result));
        assertEquals(USER_TEXT, tool.getContext().getToolCallHistory().get(0).getText());
        assertTrue(tool.getContext().getContext().containsKey(ToolContext.TOOL_CALL_HISTORY));
    }

    @Test
    void presetToolContextIsKeptAlongsideHistory() {
        ContextTool tool = new ContextTool();
        DefaultToolCallingChatOptions options = new DefaultToolCallingChatOptions();
        Map<String, Object> preset = new HashMap<>();
        preset.put(TRACE_KEY, TRACE_VALUE);
        options.setToolContext(preset);

        ToolExecutionResult result = execute(tool, options);

        assertEquals(jsonText(RESULT), responseText(result));
        assertEquals(TRACE_VALUE, tool.getContext().getContext().get(TRACE_KEY));
        assertEquals(2, tool.getContext().getToolCallHistory().size());
        Message assistant = tool.getContext().getToolCallHistory().get(1);
        assertTrue(assistant instanceof AssistantMessage);
        assertEquals(TOOL_NAME, ((AssistantMessage) assistant).getToolCalls().get(0).name());
    }

    private ToolExecutionResult execute(ContextTool tool, DefaultToolCallingChatOptions options) {
        options.setToolCallbacks(List.of(ToolCallbacks.from(tool)));
        Prompt prompt = new Prompt(new UserMessage(USER_TEXT), options);
        AssistantMessage.ToolCall toolCall = new AssistantMessage.ToolCall(CALL_ID, TOOL_CALL_TYPE, TOOL_NAME,
                ARGUMENTS);
        AssistantMessage assistant = new AssistantMessage("", Map.of(), List.of(toolCall));
        ChatResponse response = new ChatResponse(List.of(new Generation(assistant)));
        return manager.executeToolCalls(prompt, response);
    }

    /** MethodToolCallback 会把 String 返回值再包一层 JSON 字符串。 */
    private String jsonText(String value) {
        return "\"" + value + "\"";
    }

    private String responseText(ToolExecutionResult result) {
        Message last = result.conversationHistory().get(result.conversationHistory().size() - 1);
        ToolResponseMessage toolMessage = (ToolResponseMessage) last;
        return toolMessage.getResponses().get(0).responseData();
    }

    /** 声明 ToolContext 参数的测试工具。 */
    public static class ContextTool {

        private ToolContext context;

        /**
         * @param note        备注
         * @param toolContext 调用上下文
         * @return 固定结果
         */
        @Tool(name = TOOL_NAME, description = "Reads the caller context.")
        public String read(String note, ToolContext toolContext) {
            this.context = toolContext;
            return RESULT;
        }

        public ToolContext getContext() {
            return context;
        }
    }
}
