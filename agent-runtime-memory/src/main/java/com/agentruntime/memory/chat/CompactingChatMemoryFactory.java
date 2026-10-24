/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org.org/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentruntime.memory.chat;

import com.agentruntime.api.memory.ChatHistorySummarizer;
import com.agentruntime.api.memory.CompactConfig;
import com.agentruntime.api.memory.MessageWritePolicy;
import com.agentruntime.api.memory.SessionSeqProvider;
import com.agentruntime.api.memory.ToolCompactionPolicyRegistry;
import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.api.repository.MessageRepository;
import com.agentruntime.api.repository.SummarySnapshotRepository;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.api.spi.ToolResultCompactor;
import com.agentruntime.memory.budget.BudgetCalculator;
import com.agentruntime.memory.compaction.CompactionFallbackHandler;
import com.agentruntime.memory.compaction.IncrementalRollupSummarizer;
import com.agentruntime.memory.compaction.StructuredExtractor;
import com.agentruntime.memory.compaction.SummaryCompactor;
import com.agentruntime.memory.compaction.SummaryContentValidator;
import com.agentruntime.memory.compaction.SummaryExecutor;
import com.agentruntime.memory.compaction.ToolResultRenderer;
import com.agentruntime.memory.compaction.TurnSelector;
import com.agentruntime.memory.write.MemoryWriteQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * CompactingChatMemory 工厂（Spring bean，持有全部基础设施）。
 * 使用者注入此工厂后调用 {@link #builder(ChatModel)} 获得流式 builder。
 *
 * <p>每次 {@link #create} 按用户选择的 {@link ChatHistorySummarizer} 构建独立的
 * {@link SummaryCompactor}（避免共享单例被并发改写）。
 */
public class CompactingChatMemoryFactory {

    private final MessageWritePolicy writePolicy;
    private final SessionSeqProvider seqProvider;
    private final MemoryWriteQueue writeQueue;
    private final MessageRepository messageRepository;
    private final TokenEstimator tokenEstimator;
    private final ObjectMapper objectMapper;
    private final SummarySnapshotRepository summaryRepository;
    private final BudgetCalculator budgetCalculator;
    private final SummaryExecutor summaryExecutor;
    private final StringRedisTemplate redis;
    private final ObservabilityRecorder recorder;
    private final List<ToolResultCompactor> toolResultCompactors;

    public CompactingChatMemoryFactory(MessageWritePolicy writePolicy, SessionSeqProvider seqProvider,
                                        MemoryWriteQueue writeQueue, MessageRepository messageRepository,
                                        TokenEstimator tokenEstimator, ObjectMapper objectMapper,
                                        SummarySnapshotRepository summaryRepository,
                                        BudgetCalculator budgetCalculator,
                                        SummaryExecutor summaryExecutor,
                                        StringRedisTemplate redis,
                                        ObservabilityRecorder recorder,
                                        List<ToolResultCompactor> toolResultCompactors) {
        this.writePolicy = writePolicy;
        this.seqProvider = seqProvider;
        this.writeQueue = writeQueue;
        this.messageRepository = messageRepository;
        this.tokenEstimator = tokenEstimator;
        this.objectMapper = objectMapper;
        this.summaryRepository = summaryRepository;
        this.budgetCalculator = budgetCalculator;
        this.summaryExecutor = summaryExecutor;
        this.redis = redis;
        this.recorder = recorder;
        this.toolResultCompactors = toolResultCompactors == null ? List.of() : toolResultCompactors;
    }

    /**
     * 创建 builder。
     *
     * @param chatModel 压缩用模型（供默认 LLM 摘要器使用；用户也可经 builder.summarizer 自定义）
     * @return builder
     */
    public CompactingChatMemoryBuilder builder(ChatModel chatModel) {
        return new CompactingChatMemoryBuilder(this, chatModel);
    }

    /**
     * 实际构建（由 builder 调用）。
     *
     * @param chatModel      模型
     * @param config         配置
     * @param policyRegistry 策略注册表
     * @param summarizer     用户自定义摘要器（null 时用默认 LLM 实现）
     * @return CompactingChatMemory
     */
    public CompactingChatMemory create(ChatModel chatModel, CompactConfig config,
                                        ToolCompactionPolicyRegistry policyRegistry,
                                        ChatHistorySummarizer summarizer) {
        MessageConverter converter = new MessageConverter(objectMapper);
        ChatHistorySummarizer effectiveSummarizer = summarizer != null
                ? summarizer
                : new IncrementalRollupSummarizer(chatModel, converter, objectMapper);
        StructuredExtractor extractor = new StructuredExtractor(chatModel, converter, objectMapper);
        CompactionFallbackHandler fallbackHandler = new CompactionFallbackHandler(extractor, effectiveSummarizer, config);
        SummaryContentValidator validator = new SummaryContentValidator();
        // TurnSelector 用 per-call config 构建，保证 builder.config(...) 覆盖的 topK/keywords 生效
        TurnSelector turnSelector = new TurnSelector(config);
        ToolResultRenderer renderer = new ToolResultRenderer(policyRegistry, objectMapper, toolResultCompactors);
        SummaryCompactor compactor = new SummaryCompactor(summaryRepository, messageRepository, writeQueue,
                budgetCalculator, tokenEstimator, config, objectMapper, redis, turnSelector,
                fallbackHandler, validator, recorder, renderer);
        return new CompactingChatMemory(chatModel, config, policyRegistry, writePolicy, seqProvider,
                writeQueue, messageRepository, tokenEstimator, converter,
                summaryRepository, compactor, budgetCalculator, summaryExecutor, objectMapper, renderer);
    }
}
