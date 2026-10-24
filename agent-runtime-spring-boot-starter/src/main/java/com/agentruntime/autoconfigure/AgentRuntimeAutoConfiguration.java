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
package com.agentruntime.autoconfigure;

import com.agentruntime.api.observability.ObservabilityRecorder;
import com.agentruntime.api.observability.ObservabilitySink;
import com.agentruntime.api.memory.MessageWritePolicy;
import com.agentruntime.api.memory.SessionSeqProvider;
import com.agentruntime.api.memory.ToolCompactionPolicyRegistry;
import com.agentruntime.api.repository.EventRepository;
import com.agentruntime.api.repository.MessageRepository;
import com.agentruntime.api.repository.SessionRepository;
import com.agentruntime.api.repository.SpanRepository;
import com.agentruntime.api.repository.SummarySnapshotRepository;
import com.agentruntime.api.repository.ToolCallRepository;
import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.api.spi.TransactionPort;
import com.agentruntime.observability.JdbcEventRepository;
import com.agentruntime.observability.JdbcObservabilitySink;
import com.agentruntime.observability.JdbcSpanRepository;
import com.agentruntime.observability.LoggingObservabilitySink;
import com.agentruntime.observability.SpanTreeObservabilityRecorder;
import com.agentruntime.observability.stream.ObservabilityStreamConsumer;
import com.agentruntime.observability.stream.ObservabilityStreamQueue;
import com.agentruntime.observability.stream.RedisStreamObservabilitySink;
import com.agentruntime.memory.budget.BudgetCalculator;
import com.agentruntime.memory.budget.LocalTokenEstimator;
import com.agentruntime.memory.chat.CompactingChatMemoryFactory;
import com.agentruntime.memory.compaction.SummaryExecutor;
import com.agentruntime.memory.persistence.jdbc.JdbcMessageRepository;
import com.agentruntime.memory.persistence.jdbc.JdbcSessionRepository;
import com.agentruntime.memory.persistence.jdbc.JdbcSummarySnapshotRepository;
import com.agentruntime.memory.persistence.jdbc.JdbcToolCallRepository;
import com.agentruntime.memory.persistence.tx.TransactionPortImpl;
import com.agentruntime.memory.write.AlwaysWritePolicy;
import com.agentruntime.memory.write.MemoryWriteQueue;
import com.agentruntime.memory.write.MemoryWriteWorker;
import com.agentruntime.memory.write.RedisSessionSeqProvider;
import com.agentruntime.capability.skill.SkillRuntime;
import com.agentruntime.runtime.AgentRuntimeFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.util.List;

/**
 * Agent Runtime Harness 自动装配。聚合 memory 子系统的默认 bean 与 AgentRuntime 工厂。
 * 依赖使用者的 DataSource / Redis（由 Spring Boot 自动配置提供）。
 */
@AutoConfiguration
@EnableScheduling
@ConditionalOnClass({JdbcTemplate.class, StringRedisTemplate.class})
public class AgentRuntimeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public SessionRepository sessionRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcSessionRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageRepository messageRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcMessageRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public SummarySnapshotRepository summarySnapshotRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcSummarySnapshotRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolCallRepository toolCallRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcToolCallRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public TransactionPort transactionPort(PlatformTransactionManager transactionManager) {
        return new TransactionPortImpl(transactionManager);
    }

    @Bean
    @ConditionalOnMissingBean
    public TokenEstimator tokenEstimator() {
        return new LocalTokenEstimator();
    }

    @Bean
    @ConditionalOnMissingBean
    public BudgetCalculator budgetCalculator() {
        return new BudgetCalculator();
    }

    @Bean
    @ConditionalOnMissingBean
    public ToolCompactionPolicyRegistry toolCompactionPolicyRegistry() {
        return ToolCompactionPolicyRegistry.withDefaults();
    }

    @Bean
    @ConditionalOnMissingBean
    public MessageWritePolicy messageWritePolicy() {
        return new AlwaysWritePolicy();
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryWriteQueue memoryWriteQueue(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        return new MemoryWriteQueue(redisTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SessionSeqProvider sessionSeqProvider(StringRedisTemplate redisTemplate, MessageRepository messageRepository) {
        return new RedisSessionSeqProvider(redisTemplate, messageRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    public MemoryWriteWorker memoryWriteWorker(MemoryWriteQueue queue, MessageRepository messageRepository,
                                                TransactionPort transactionPort, StringRedisTemplate redisTemplate) {
        return new MemoryWriteWorker(queue, messageRepository, transactionPort, redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public SummaryExecutor summaryExecutor() {
        return new SummaryExecutor();
    }

    @Bean
    @ConditionalOnMissingBean
    public CompactingChatMemoryFactory compactingChatMemoryFactory(MessageWritePolicy writePolicy,
                                                                     SessionSeqProvider seqProvider,
                                                                     MemoryWriteQueue writeQueue,
                                                                     MessageRepository messageRepository,
                                                                     TokenEstimator tokenEstimator,
                                                                     ObjectMapper objectMapper,
                                                                     SummarySnapshotRepository summarySnapshotRepository,
                                                                     BudgetCalculator budgetCalculator,
                                                                     SummaryExecutor summaryExecutor,
                                                                     StringRedisTemplate redisTemplate,
                                                                     ObservabilityRecorder recorder) {
        return new CompactingChatMemoryFactory(writePolicy, seqProvider, writeQueue, messageRepository,
                tokenEstimator, objectMapper, summarySnapshotRepository, budgetCalculator,
                summaryExecutor, redisTemplate, recorder);
    }

    @Bean
    @ConditionalOnMissingBean
    public AgentRuntimeFactory agentRuntimeFactory(SessionRepository sessionRepository,
                                                    ObservabilityRecorder recorder,
                                                    ObjectMapper objectMapper,
                                                    com.agentruntime.core.tool.ToolMetaRegistry toolMetaRegistry,
                                                    com.agentruntime.api.spi.ToolFailureClassifier toolFailureClassifier,
                                                    com.agentruntime.core.tool.ToolGovernanceDefaults governanceDefaults,
                                                    TokenEstimator tokenEstimator,
                                                    ObjectProvider<SkillRuntime> skillRuntime) {
        return new AgentRuntimeFactory(sessionRepository, recorder, objectMapper,
                toolMetaRegistry, toolFailureClassifier, governanceDefaults, tokenEstimator,
                skillRuntime.getIfAvailable());
    }

    // ----- Observability -----

    @Bean
    @ConditionalOnMissingBean
    public SpanRepository spanRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcSpanRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventRepository eventRepository(JdbcTemplate jdbcTemplate) {
        return new JdbcEventRepository(jdbcTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    public LoggingObservabilitySink loggingObservabilitySink() {
        return new LoggingObservabilitySink();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent-runtime.observability.persist", name = "mode", havingValue = "sync")
    public JdbcObservabilitySink jdbcObservabilitySink(SpanRepository spanRepository, EventRepository eventRepository) {
        return new JdbcObservabilitySink(spanRepository, eventRepository);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "agent-runtime.observability.persist", name = "mode",
            havingValue = "async", matchIfMissing = true)
    public ObservabilityStreamQueue observabilityStreamQueue(StringRedisTemplate redisTemplate) {
        return new ObservabilityStreamQueue(redisTemplate);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ObservabilityStreamQueue.class)
    public RedisStreamObservabilitySink redisStreamObservabilitySink(ObservabilityStreamQueue queue,
                                                                     ObjectMapper objectMapper) {
        return new RedisStreamObservabilitySink(queue, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(ObservabilityStreamQueue.class)
    public ObservabilityStreamConsumer observabilityStreamConsumer(ObservabilityStreamQueue queue,
                                                                    SpanRepository spanRepository,
                                                                    EventRepository eventRepository,
                                                                    TransactionPort transactionPort,
                                                                    StringRedisTemplate redisTemplate,
                                                                    ObjectMapper objectMapper) {
        return new ObservabilityStreamConsumer(queue, spanRepository, eventRepository,
                transactionPort, redisTemplate, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public ObservabilityRecorder observabilityRecorder(List<ObservabilitySink> sinks) {
        return new SpanTreeObservabilityRecorder(sinks);
    }
}
