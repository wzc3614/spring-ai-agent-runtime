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
package com.agentruntime.core.budget;

import com.agentruntime.api.budget.TurnBudget;
import com.agentruntime.api.budget.TurnBudgetConstants;
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.TurnBudgetReasonEnum;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.core.springai.SpanContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 有预算的工具调用模型。关闭下游的内部工具循环，由本类按步执行：
 * 一次模型调用算一步，同一次响应里的并行工具算同一步。
 *
 * <p>步数或累计 token 触顶且模型仍要求调用工具时，不再执行工具、也不再请求模型，
 * 返回固定回复并在当前 TURN span 上记录 {@code TURN_BUDGET_EXCEEDED}。
 * 已经返回最终文本的那一次调用即使超出 token，仍把这段回复交给调用方。
 *
 * <p>下游必须遵守 {@code internalToolExecutionEnabled=false}，否则循环仍发生在下游内部，
 * 本类只能看到合并后的最后一次返回。
 */
public class BoundedToolCallingChatModel implements ChatModel {

    private static final Logger logger = LoggerFactory.getLogger(BoundedToolCallingChatModel.class);

    private final ChatModel delegate;

    private final ToolCallingManager toolCallingManager;

    private final TurnBudget budget;

    private final ObservabilityRecorder recorder;

    private final TokenEstimator tokenEstimator;

    public BoundedToolCallingChatModel(ChatModel delegate, ToolCallingManager toolCallingManager,
                                       TurnBudget budget, ObservabilityRecorder recorder,
                                       TokenEstimator tokenEstimator) {
        Assert.notNull(delegate, "delegate cannot be null");
        Assert.notNull(toolCallingManager, "toolCallingManager cannot be null");
        Assert.notNull(budget, "budget cannot be null");
        Assert.notNull(recorder, "recorder cannot be null");
        Assert.notNull(tokenEstimator, "tokenEstimator cannot be null");
        Assert.isTrue(budget.getMaxModelCalls() > 0, "maxModelCalls must be positive");
        Assert.isTrue(budget.getMaxTurnTokens() > 0, "maxTurnTokens must be positive");
        this.delegate = delegate;
        this.toolCallingManager = toolCallingManager;
        this.budget = budget;
        this.recorder = recorder;
        this.tokenEstimator = tokenEstimator;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Prompt current = disableInternalToolExecution(prompt);
        TurnLoopState state = new TurnLoopState();
        while (true) {
            TurnBudgetReasonEnum blocked = exceededReason(state);
            if (blocked != null) {
                return stop(state, blocked);
            }
            ChatResponse response = delegate.call(current);
            Continuation continuation = decide(current, response, state);
            if (continuation.isPassThrough()) {
                return response;
            }
            if (continuation.isFinished()) {
                return continuation.getResponse();
            }
            current = continuation.getNext();
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return streamFrom(disableInternalToolExecution(prompt), new TurnLoopState());
    }

    /**
     * 流式一轮。普通文本块原样往外推；带工具调用的块先留下，本步结束后再决定是否进入下一步。
     * 这样最终回答仍按 token 流出，工具步骤不会把空片段或 tool call 送给订阅者。
     */
    private Flux<ChatResponse> streamFrom(Prompt prompt, TurnLoopState state) {
        TurnBudgetReasonEnum blocked = exceededReason(state);
        if (blocked != null) {
            return Flux.just(stop(state, blocked));
        }
        List<ChatResponse> chunks = new ArrayList<>();
        AtomicReference<ChatResponse> aggregated = new AtomicReference<>();
        return new MessageAggregator()
                .aggregate(delegate.stream(prompt), aggregated::set)
                .doOnNext(chunks::add)
                .filter(this::shouldEmitLive)
                .concatWith(Flux.defer(() -> afterStreamRound(prompt, state, aggregated.get(), chunks)));
    }

    private Flux<ChatResponse> afterStreamRound(Prompt prompt, TurnLoopState state, ChatResponse aggregated,
                                                List<ChatResponse> chunks) {
        // MessageAggregator 只拼接文本，工具调用留在原始 chunk 上。
        // 文本块已经在 shouldEmitLive 里推给订阅者，这里只处理工具后续或触顶回复。
        ChatResponse response = responseForDecision(aggregated, chunks);
        Continuation continuation = decide(prompt, response, state);
        if (continuation.isPassThrough()) {
            return Flux.empty();
        }
        if (continuation.isFinished()) {
            ChatResponse terminal = continuation.getResponse();
            return terminal == null ? Flux.empty() : Flux.just(terminal);
        }
        return streamFrom(continuation.getNext(), state);
    }

    /** 没有工具调用、且带文本的块才向订阅者输出。 */
    private boolean shouldEmitLive(ChatResponse chunk) {
        if (chunk == null || chunk.hasToolCalls() || chunk.getResult() == null
                || chunk.getResult().getOutput() == null) {
            return false;
        }
        String text = chunk.getResult().getOutput().getText();
        return text != null && !text.isEmpty();
    }

    /**
     * 记下本步消耗。没有工具调用时结束；仍要工具且预算已满时停止；否则执行工具并给出下一步 prompt。
     */
    private Continuation decide(Prompt prompt, ChatResponse response, TurnLoopState state) {
        state.add(tokensOf(prompt, response));
        if (response == null || !response.hasToolCalls()) {
            return Continuation.passThrough();
        }
        TurnBudgetReasonEnum reason = exceededReason(state);
        if (reason != null) {
            return Continuation.finish(stop(state, reason));
        }
        ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, response);
        if (result.returnDirect()) {
            return Continuation.finish(directResponse(response, result));
        }
        return Continuation.proceed(new Prompt(result.conversationHistory(), prompt.getOptions()));
    }

    private TurnBudgetReasonEnum exceededReason(TurnLoopState state) {
        if (state.getModelCalls() >= budget.getMaxModelCalls()) {
            return TurnBudgetReasonEnum.STEP_LIMIT;
        }
        if (state.getTotalTokens() >= budget.getMaxTurnTokens()) {
            return TurnBudgetReasonEnum.TOKEN_LIMIT;
        }
        return null;
    }

    private ChatResponse stop(TurnLoopState state, TurnBudgetReasonEnum reason) {
        state.setReason(reason);
        recordExceeded(state);
        return stopResponse();
    }

    private void recordExceeded(TurnLoopState state) {
        logger.info("turn budget exceeded, reason={}, modelCalls={}, totalTokens={}",
                state.getReason().getCode(), state.getModelCalls(), state.getTotalTokens());
        String parentSpanId = SpanContextHolder.parentSpanId();
        if (parentSpanId == null) {
            return;
        }
        recorder.recordEvent(parentSpanId, EventTypeEnum.TURN_BUDGET_EXCEEDED, payload(state));
    }

    private String payload(TurnLoopState state) {
        return "{\"reason\":\"" + state.getReason().getCode()
                + "\",\"modelCalls\":" + state.getModelCalls()
                + ",\"totalTokens\":" + state.getTotalTokens()
                + ",\"maxModelCalls\":" + budget.getMaxModelCalls()
                + ",\"maxTurnTokens\":" + budget.getMaxTurnTokens() + "}";
    }

    private ChatResponse stopResponse() {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(TurnBudgetConstants.STOP_TEXT))));
    }

    private ChatResponse directResponse(ChatResponse source, ToolExecutionResult result) {
        return ChatResponse.builder()
                .from(source)
                .generations(ToolExecutionResult.buildGenerations(result))
                .build();
    }

    /**
     * 复制选项并关闭内部工具循环。调用方传入的 options 不被修改。
     */
    private Prompt disableInternalToolExecution(Prompt prompt) {
        ChatOptions options = prompt.getOptions();
        if (options == null) {
            return new Prompt(prompt.getInstructions(), disabledOptions(null));
        }
        ChatOptions copied = options.copy();
        if (copied instanceof ToolCallingChatOptions toolOptions) {
            toolOptions.setInternalToolExecutionEnabled(Boolean.FALSE);
            return new Prompt(prompt.getInstructions(), copied);
        }
        return new Prompt(prompt.getInstructions(), disabledOptions(copied));
    }

    private DefaultToolCallingChatOptions disabledOptions(ChatOptions source) {
        DefaultToolCallingChatOptions options = new DefaultToolCallingChatOptions();
        options.setInternalToolExecutionEnabled(Boolean.FALSE);
        if (source == null) {
            return options;
        }
        options.setModel(source.getModel());
        options.setFrequencyPenalty(source.getFrequencyPenalty());
        options.setMaxTokens(source.getMaxTokens());
        options.setPresencePenalty(source.getPresencePenalty());
        options.setStopSequences(source.getStopSequences());
        options.setTemperature(source.getTemperature());
        options.setTopK(source.getTopK());
        options.setTopP(source.getTopP());
        return options;
    }

    /**
     * 优先用供应商返回的 prompt + completion。两者都缺失或都为 0 时，用本地估算兜底。
     */
    private int tokensOf(Prompt prompt, ChatResponse response) {
        int reported = reportedTokens(response);
        if (reported > 0) {
            return reported;
        }
        return estimatedTokens(prompt, response);
    }

    private int reportedTokens(ChatResponse response) {
        if (response == null || response.getMetadata() == null || response.getMetadata().getUsage() == null) {
            return 0;
        }
        Usage usage = response.getMetadata().getUsage();
        int promptTokens = usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
        int completionTokens = usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens();
        return promptTokens + completionTokens;
    }

    private int estimatedTokens(Prompt prompt, ChatResponse response) {
        int total = 0;
        if (prompt != null && !CollectionUtils.isEmpty(prompt.getInstructions())) {
            for (Message message : prompt.getInstructions()) {
                total += tokenEstimator.estimate(message.getText());
            }
        }
        if (response != null && response.getResult() != null && response.getResult().getOutput() != null) {
            total += tokenEstimator.estimate(response.getResult().getOutput().getText());
        }
        return total;
    }

    /**
     * 决策用的响应：有工具调用时保留最后一块带 tool call 的原文，并把聚合后的 usage 贴回去。
     * 没有工具调用时用聚合结果，这样 token 按整段回复计算。
     */
    private ChatResponse responseForDecision(ChatResponse aggregated, List<ChatResponse> chunks) {
        ChatResponse withTools = lastWithToolCalls(chunks);
        if (withTools == null) {
            return aggregated != null ? aggregated : lastChunk(chunks);
        }
        if (aggregated == null || aggregated.getMetadata() == null) {
            return withTools;
        }
        return new ChatResponse(withTools.getResults(), aggregated.getMetadata());
    }

    private ChatResponse lastWithToolCalls(List<ChatResponse> chunks) {
        if (CollectionUtils.isEmpty(chunks)) {
            return null;
        }
        ChatResponse found = null;
        for (ChatResponse chunk : chunks) {
            if (chunk != null && chunk.hasToolCalls()) {
                found = chunk;
            }
        }
        return found;
    }

    private ChatResponse lastChunk(List<ChatResponse> chunks) {
        if (CollectionUtils.isEmpty(chunks)) {
            return null;
        }
        return chunks.get(chunks.size() - 1);
    }

    /** 本轮循环的累计计数。每次 call / stream 各自新建，不跨请求共享。 */
    private static final class TurnLoopState {

        private int modelCalls;

        private int totalTokens;

        private TurnBudgetReasonEnum reason;

        void add(int tokens) {
            this.modelCalls++;
            this.totalTokens += Math.max(tokens, 0);
        }

        int getModelCalls() {
            return modelCalls;
        }

        int getTotalTokens() {
            return totalTokens;
        }

        TurnBudgetReasonEnum getReason() {
            return reason;
        }

        void setReason(TurnBudgetReasonEnum reason) {
            this.reason = reason;
        }
    }

    /** 循环的下一步：把当前回复交出去、结束在合成回复上，或带着新 prompt 继续。 */
    private static final class Continuation {

        private final ChatResponse response;

        private final Prompt next;

        private final boolean passThrough;

        private Continuation(ChatResponse response, Prompt next, boolean passThrough) {
            this.response = response;
            this.next = next;
            this.passThrough = passThrough;
        }

        static Continuation passThrough() {
            return new Continuation(null, null, true);
        }

        static Continuation finish(ChatResponse response) {
            return new Continuation(response, null, false);
        }

        static Continuation proceed(Prompt next) {
            return new Continuation(null, next, false);
        }

        boolean isPassThrough() {
            return passThrough;
        }

        boolean isFinished() {
            return next == null;
        }

        ChatResponse getResponse() {
            return response;
        }

        Prompt getNext() {
            return next;
        }
    }
}
