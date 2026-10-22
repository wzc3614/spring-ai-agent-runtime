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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.time.Duration;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.observation.DefaultToolCallingObservationConvention;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.ai.tool.observation.ToolCallingObservationConvention;
import org.springframework.ai.tool.observation.ToolCallingObservationDocumentation;
import org.springframework.ai.tool.resolution.DelegatingToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import com.agentruntime.core.tool.DefaultToolMetaRegistry;
import com.agentruntime.core.tool.ToolGovernanceDefaults;
import com.agentruntime.core.tool.ToolMeta;
import com.agentruntime.core.tool.ToolMetaRegistry;

/**
 * Spring AI {@link ToolCallingManager} 的并行实现。用 {@link ParallelToolExecutor}
 * （虚拟线程 + Semaphore 限并发）并发执行一轮内多个独立 tool call，结果按模型请求顺序对齐。
 * 其余行为（工具解析、ToolCalling observation、异常处理、会话历史拼接）与 Spring AI 的
 * {@code DefaultToolCallingManager} 保持一致，便于无缝替换。
 *
 * <p>观测装饰器链（如 {@code ObservabilityToolCallback}）保持不变：本管理器调用的是
 * {@link ToolCallingChatOptions#getToolCallbacks()} 里已包装好的回调，因此每个工具的 span
 * 与事件仍挂在自己的子树下。
 */
public class ParallelToolCallingManager implements ToolCallingManager {

    private static final Logger logger = LoggerFactory.getLogger(ParallelToolCallingManager.class);

    /** 一轮内并发执行工具数的默认上限。 */
    public static final int DEFAULT_MAX_CONCURRENCY = 8;

    // @formatter:off
    private static final ObservationRegistry DEFAULT_OBSERVATION_REGISTRY = ObservationRegistry.NOOP;

    private static final ToolCallingObservationConvention DEFAULT_OBSERVATION_CONVENTION =
            new DefaultToolCallingObservationConvention();

    private static final ToolCallbackResolver DEFAULT_TOOL_CALLBACK_RESOLVER =
            new DelegatingToolCallbackResolver(List.of());

    private static final ToolExecutionExceptionProcessor DEFAULT_TOOL_EXECUTION_EXCEPTION_PROCESSOR =
            DefaultToolExecutionExceptionProcessor.builder().build();
    // @formatter:on

    private final ObservationRegistry observationRegistry;

    private final ToolCallbackResolver toolCallbackResolver;

    private final ToolExecutionExceptionProcessor toolExecutionExceptionProcessor;

    private final ParallelToolExecutor parallelToolExecutor;

    private final int maxConcurrency;

    private final ToolMetaRegistry toolMetaRegistry;

    private final ToolGovernanceDefaults governanceDefaults;

    private ToolCallingObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

    public ParallelToolCallingManager(ObservationRegistry observationRegistry,
            ToolCallbackResolver toolCallbackResolver,
            ToolExecutionExceptionProcessor toolExecutionExceptionProcessor,
            ParallelToolExecutor parallelToolExecutor, int maxConcurrency,
            ToolMetaRegistry toolMetaRegistry, ToolGovernanceDefaults governanceDefaults) {
        Assert.notNull(observationRegistry, "observationRegistry cannot be null");
        Assert.notNull(toolCallbackResolver, "toolCallbackResolver cannot be null");
        Assert.notNull(toolExecutionExceptionProcessor, "toolExecutionExceptionProcessor cannot be null");
        Assert.notNull(parallelToolExecutor, "parallelToolExecutor cannot be null");
        this.observationRegistry = observationRegistry;
        this.toolCallbackResolver = toolCallbackResolver;
        this.toolExecutionExceptionProcessor = toolExecutionExceptionProcessor;
        this.parallelToolExecutor = parallelToolExecutor;
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.toolMetaRegistry = toolMetaRegistry != null ? toolMetaRegistry : new DefaultToolMetaRegistry();
        this.governanceDefaults = governanceDefaults != null ? governanceDefaults : new ToolGovernanceDefaults();
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        Assert.notNull(chatOptions, "chatOptions cannot be null");

        List<ToolCallback> toolCallbacks = new ArrayList<>(chatOptions.getToolCallbacks());
        for (String toolName : chatOptions.getToolNames()) {
            // 跳过已作为 ToolCallback 出现在 options 中的工具名，避免重复。
            if (chatOptions.getToolCallbacks()
                .stream()
                .anyMatch(tool -> tool.getToolDefinition().name().equals(toolName))) {
                continue;
            }
            ToolCallback toolCallback = this.toolCallbackResolver.resolve(toolName);
            if (toolCallback == null) {
                throw new IllegalStateException("No ToolCallback found for tool name: " + toolName);
            }
            toolCallbacks.add(toolCallback);
        }

        return toolCallbacks.stream().map(ToolCallback::getToolDefinition).toList();
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        Assert.notNull(prompt, "prompt cannot be null");
        Assert.notNull(chatResponse, "chatResponse cannot be null");

        Optional<Generation> toolCallGeneration = chatResponse.getResults()
            .stream()
            .filter(g -> !CollectionUtils.isEmpty(g.getOutput().getToolCalls()))
            .findFirst();

        if (toolCallGeneration.isEmpty()) {
            throw new IllegalStateException("No tool call requested by the chat model");
        }

        AssistantMessage assistantMessage = toolCallGeneration.get().getOutput();

        ToolContext toolContext = buildToolContext(prompt, assistantMessage);

        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();

        List<ToolCallback> optionCallbacks = (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions)
                ? toolCallingChatOptions.getToolCallbacks() : List.of();

        // 先解析所有回调（顺序、低开销、遇错即抛），并计算合并后的 returnDirect
        // 标志（所有请求工具的 returnDirect 取 AND，与默认实现一致）。
        List<ToolCallback> resolvedCallbacks = new ArrayList<>(toolCalls.size());
        boolean returnDirect = true;
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String toolName = toolCall.name();
            ToolCallback toolCallback = optionCallbacks.stream()
                .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
                .findFirst()
                .orElseGet(() -> this.toolCallbackResolver.resolve(toolName));
            if (toolCallback == null) {
                throw new IllegalStateException("No ToolCallback found for tool name: " + toolName);
            }
            resolvedCallbacks.add(toolCallback);
            returnDirect = returnDirect && toolCallback.getToolMetadata().returnDirect();
        }

        // 并发执行各工具；结果按模型请求顺序对齐。
        List<Supplier<String>> tasks = new ArrayList<>(toolCalls.size());
        List<Duration> timeouts = new ArrayList<>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++) {
            final ToolCallback toolCallback = resolvedCallbacks.get(i);
            final String toolInputArguments = toolCalls.get(i).arguments();
            tasks.add(() -> executeSingleTool(toolCallback, toolInputArguments, toolContext));
            timeouts.add(resolveTimeout(toolCallback.getToolDefinition().name()));
        }
        List<TaskResult<String>> results = this.parallelToolExecutor.invoke(tasks, this.maxConcurrency, timeouts);

        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>(toolCalls.size());
        for (int i = 0; i < toolCalls.size(); i++) {
            AssistantMessage.ToolCall toolCall = toolCalls.get(i);
            String result = toToolResponseContent(toolCall.name(), results.get(i));
            toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), result));
        }

        ToolResponseMessage toolResponseMessage = new ToolResponseMessage(toolResponses, Map.of());

        List<Message> conversationHistory = buildConversationHistoryAfterToolExecution(prompt.getInstructions(),
                assistantMessage, toolResponseMessage);

        return ToolExecutionResult.builder()
            .conversationHistory(conversationHistory)
            .returnDirect(returnDirect)
            .build();
    }

    /**
     * 在 ToolCalling observation 下执行单个工具调用，逻辑与默认管理器一致。
     * 可安全并发执行：每次调用构建自己的 observation 上下文，registry/convention 为只读共享状态。
     */
    private String executeSingleTool(ToolCallback toolCallback, String toolInputArguments, ToolContext toolContext) {
        logger.debug("Executing tool call: {}", toolCallback.getToolDefinition().name());

        ToolCallingObservationContext observationContext = ToolCallingObservationContext.builder()
            .toolDefinition(toolCallback.getToolDefinition())
            .toolMetadata(toolCallback.getToolMetadata())
            .toolCallArguments(toolInputArguments)
            .build();

        return ToolCallingObservationDocumentation.TOOL_CALL
            .observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
                    this.observationRegistry)
            .observe(() -> {
                String toolResult;
                try {
                    toolResult = toolCallback.call(toolInputArguments, toolContext);
                }
                catch (ToolExecutionException ex) {
                    toolResult = this.toolExecutionExceptionProcessor.process(ex);
                }
                observationContext.setToolCallResult(toolResult);
                return toolResult;
            });
    }

    /** 按工具元数据解析单次调用总超时；meta 未配置时用全局默认。 */
    private Duration resolveTimeout(String toolName) {
        ToolMeta meta = toolMetaRegistry.getMeta(toolName);
        Long timeoutMs = meta.getTimeoutMs();
        if (timeoutMs != null && timeoutMs > 0) {
            return Duration.ofMillis(timeoutMs);
        }
        return Duration.ofMillis(Math.max(1L, governanceDefaults.getDefaultTimeoutMs()));
    }

    /**
     * 把单个工具的 {@link TaskResult} 映射为回填模型的字符串内容。
     * 成功 → 工具返回值；超时 → 结构化 tool_timeout 错误；失败 → 失败摘要。
     * 单工具超时/失败不抛出，保证同轮其它工具结果照常返回。
     */
    private String toToolResponseContent(String toolName, TaskResult<String> result) {
        if (result == null) {
            return "";
        }
        if (result.isSuccess()) {
            String value = result.getValue();
            return value != null ? value : "";
        }
        if (result.isTimedOut()) {
            long timeoutMs = resolveTimeoutMs(toolName);
            return "{\"error\":\"tool_timeout\",\"tool\":\"" + toolName + "\",\"timeoutMs\":" + timeoutMs + "}";
        }
        Throwable error = result.getError();
        String message = error != null && error.getMessage() != null ? error.getMessage() : error.getClass().getName();
        return "{\"error\":\"tool_failed\",\"tool\":\"" + toolName + "\",\"cause\":\"" + escape(message) + "\"}";
    }

    private long resolveTimeoutMs(String toolName) {
        ToolMeta meta = toolMetaRegistry.getMeta(toolName);
        Long timeoutMs = meta.getTimeoutMs();
        if (timeoutMs != null && timeoutMs > 0) {
            return timeoutMs;
        }
        return Math.max(1L, governanceDefaults.getDefaultTimeoutMs());
    }

    /** 最小化转义，避免错误信息里的引号破坏回填模型的 JSON。 */
    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static ToolContext buildToolContext(Prompt prompt, AssistantMessage assistantMessage) {
        Map<String, Object> toolContextMap = Map.of();

        if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions
                && !CollectionUtils.isEmpty(toolCallingChatOptions.getToolContext())) {
            toolContextMap = new HashMap<>(toolCallingChatOptions.getToolContext());
            toolContextMap.put(ToolContext.TOOL_CALL_HISTORY,
                    buildConversationHistoryBeforeToolExecution(prompt, assistantMessage));
        }

        return new ToolContext(toolContextMap);
    }

    private static List<Message> buildConversationHistoryBeforeToolExecution(Prompt prompt,
            AssistantMessage assistantMessage) {
        List<Message> messageHistory = new ArrayList<>(prompt.copy().getInstructions());
        messageHistory.add(new AssistantMessage(assistantMessage.getText(), assistantMessage.getMetadata(),
                assistantMessage.getToolCalls()));
        return messageHistory;
    }

    private List<Message> buildConversationHistoryAfterToolExecution(List<Message> previousMessages,
            AssistantMessage assistantMessage, ToolResponseMessage toolResponseMessage) {
        List<Message> messages = new ArrayList<>(previousMessages);
        messages.add(assistantMessage);
        messages.add(toolResponseMessage);
        return messages;
    }

    public void setObservationConvention(ToolCallingObservationConvention observationConvention) {
        this.observationConvention = observationConvention;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private ObservationRegistry observationRegistry = DEFAULT_OBSERVATION_REGISTRY;

        private ToolCallbackResolver toolCallbackResolver = DEFAULT_TOOL_CALLBACK_RESOLVER;

        private ToolExecutionExceptionProcessor toolExecutionExceptionProcessor = DEFAULT_TOOL_EXECUTION_EXCEPTION_PROCESSOR;

        private ParallelToolExecutor parallelToolExecutor = new ParallelToolExecutor();

        private int maxConcurrency = DEFAULT_MAX_CONCURRENCY;

        private ToolMetaRegistry toolMetaRegistry;

        private ToolGovernanceDefaults governanceDefaults;

        private Builder() {
        }

        public Builder observationRegistry(ObservationRegistry observationRegistry) {
            this.observationRegistry = observationRegistry;
            return this;
        }

        public Builder toolCallbackResolver(ToolCallbackResolver toolCallbackResolver) {
            this.toolCallbackResolver = toolCallbackResolver;
            return this;
        }

        public Builder toolExecutionExceptionProcessor(
                ToolExecutionExceptionProcessor toolExecutionExceptionProcessor) {
            this.toolExecutionExceptionProcessor = toolExecutionExceptionProcessor;
            return this;
        }

        public Builder parallelToolExecutor(ParallelToolExecutor parallelToolExecutor) {
            this.parallelToolExecutor = parallelToolExecutor;
            return this;
        }

        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        public Builder toolMetaRegistry(ToolMetaRegistry toolMetaRegistry) {
            this.toolMetaRegistry = toolMetaRegistry;
            return this;
        }

        public Builder governanceDefaults(ToolGovernanceDefaults governanceDefaults) {
            this.governanceDefaults = governanceDefaults;
            return this;
        }

        public ParallelToolCallingManager build() {
            return new ParallelToolCallingManager(this.observationRegistry, this.toolCallbackResolver,
                    this.toolExecutionExceptionProcessor, this.parallelToolExecutor, this.maxConcurrency,
                    this.toolMetaRegistry, this.governanceDefaults);
        }
    }
}
