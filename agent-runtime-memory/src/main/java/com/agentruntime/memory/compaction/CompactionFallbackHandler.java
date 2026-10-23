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
import com.agentruntime.api.domain.StructuredExtraction;
import com.agentruntime.api.domain.SummaryContent;
import com.agentruntime.api.enums.CompactionFailureKindEnum;
import com.agentruntime.api.memory.ChatHistorySummarizer;
import com.agentruntime.api.memory.CompactConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 压缩管道降级处理器：超时 / 非法 JSON 重试、上下文不足分批合并、异常分类。
 *
 * <p>第一层提取失败由调用方标 DEGRADED 后继续；第二层摘要失败抛 {@link CompactionException}，
 * 由调用方标 FAILED 并保留原文。绝不静默丢弃 Turn。
 */
public class CompactionFallbackHandler {

    private static final Logger log = LoggerFactory.getLogger(CompactionFallbackHandler.class);

    private final StructuredExtractor extractor;
    private final ChatHistorySummarizer summarizer;
    private final CompactConfig config;

    public CompactionFallbackHandler(StructuredExtractor extractor, ChatHistorySummarizer summarizer,
                                     CompactConfig config) {
        this.extractor = extractor;
        this.summarizer = summarizer;
        this.config = config;
    }

    /**
     * 第一层提取：超时或非法 JSON 重试一次，仍失败则抛异常（调用方标 DEGRADED 并继续）。
     *
     * @param sessionId 会话 ID
     * @param messages  待压缩 Turn
     * @return 提取结果
     */
    public StructuredExtraction extractWithRetry(String sessionId, List<AgentMessage> messages) {
        return retryTransient(() -> extractor.extract(sessionId, messages));
    }

    /**
     * 第二层增量摘要：超时 / 非法 JSON 重试一次；上下文不足则按 Turn 对半分批再合并。
     * 单条 Turn 仍溢出时抛异常，禁止静默丢弃。
     *
     * @param sessionId   会话 ID
     * @param existing    既有摘要
     * @param extraction  第一层提取（可为 null）
     * @param messages    待压缩 Turn
     * @return 摘要结果（含是否分批）
     */
    public SummarizeResult summarizeWithFallback(String sessionId, SummaryContent existing,
                                                 StructuredExtraction extraction, List<AgentMessage> messages) {
        SummarizeResult result = new SummarizeResult();
        try {
            result.setContent(retryTransient(() -> summarizer.summarize(sessionId, existing, extraction, messages)));
            result.setChunked(false);
            return result;
        } catch (CompactionException e) {
            if (e.getKind() != CompactionFailureKindEnum.CONTEXT_OVERFLOW) {
                throw e;
            }
            log.warn("summarizer context overflow, split by turn, sessionId={}, size={}",
                    sessionId, messages.size());
            result.setContent(summarizeByChunks(sessionId, existing, extraction, messages));
            result.setChunked(true);
            return result;
        }
    }

    /**
     * 按 Turn 对半分批摘要再合并。单条仍溢出则失败，不丢弃。
     */
    private SummaryContent summarizeByChunks(String sessionId, SummaryContent existing,
                                             StructuredExtraction extraction, List<AgentMessage> messages) {
        if (CollectionUtils.isEmpty(messages) || messages.size() == 1) {
            throw new CompactionException(CompactionFailureKindEnum.CONTEXT_OVERFLOW,
                    "single turn still exceeds summarizer context, keep original");
        }
        int mid = messages.size() / 2;
        List<AgentMessage> left = messages.subList(0, mid);
        List<AgentMessage> right = messages.subList(mid, messages.size());
        SummarizeResult leftResult = summarizeWithFallback(sessionId, existing, extraction, left);
        // 后半段在左半摘要之上增量合并，提取结果只喂给第一批
        SummarizeResult rightResult = summarizeWithFallback(sessionId, leftResult.getContent(), null, right);
        return rightResult.getContent();
    }

    /**
     * 超时或非法 JSON 按配置重试；上下文不足直接抛出，不在此重试整包。
     */
    private <T> T retryTransient(Supplier<T> supplier) {
        int retries = Math.max(0, config.getMaxRetries());
        CompactionException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            try {
                return timed(supplier);
            } catch (CompactionException e) {
                last = e;
                if (e.getKind() == CompactionFailureKindEnum.CONTEXT_OVERFLOW) {
                    throw e;
                }
                if (!isTransient(e.getKind()) || attempt >= retries) {
                    throw e;
                }
                log.warn("transient compaction error, retry, kind={}, attempt={}",
                        e.getKind().getCode(), attempt + 1);
            }
        }
        throw last;
    }

    private boolean isTransient(CompactionFailureKindEnum kind) {
        return kind == CompactionFailureKindEnum.TIMEOUT
                || kind == CompactionFailureKindEnum.INVALID_JSON;
    }

    private <T> T timed(Supplier<T> supplier) {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<T> future = executor.submit(supplier::get);
            return future.get(config.getLlmCallTimeoutMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new CompactionException(CompactionFailureKindEnum.TIMEOUT, "llm call timed out", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CompactionException(CompactionFailureKindEnum.TIMEOUT, "llm call interrupted", e);
        } catch (ExecutionException e) {
            throw map(e.getCause());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 把底层异常映射为带 kind 的 {@link CompactionException}。
     *
     * @param throwable 原始异常
     * @return 分类后的压缩异常
     */
    public CompactionException map(Throwable throwable) {
        if (throwable instanceof CompactionException) {
            return (CompactionException) throwable;
        }
        String message = rootMessage(throwable);
        if (containsOverflowKeyword(message)) {
            return new CompactionException(CompactionFailureKindEnum.CONTEXT_OVERFLOW, message, throwable);
        }
        return new CompactionException(CompactionFailureKindEnum.OTHER, message, throwable);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }

    private boolean containsOverflowKeyword(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        for (String keyword : CompactionConstants.CONTEXT_OVERFLOW_KEYWORDS) {
            if (lower.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
