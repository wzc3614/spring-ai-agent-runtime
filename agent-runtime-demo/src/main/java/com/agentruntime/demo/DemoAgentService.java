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
package com.agentruntime.demo;

import com.agentruntime.api.lifecycle.AgentLifecycleListener;
import com.agentruntime.api.memory.CompactConfig;
import com.agentruntime.api.memory.ToolCompactionPolicyRegistry;
import com.agentruntime.api.memory.ToolCompactionRule;
import com.agentruntime.core.tool.BackoffPolicy;
import com.agentruntime.core.tool.ToolMeta;
import com.agentruntime.core.tool.ToolMetaRegistry;
import com.agentruntime.memory.chat.CompactingChatMemory;
import com.agentruntime.memory.chat.CompactingChatMemoryFactory;
import com.agentruntime.runtime.AgentRuntime;
import com.agentruntime.runtime.AgentRuntimeFactory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 演示 Agent 服务：用工厂 bean 组装 CompactingChatMemory + AgentRuntime。
 */
@Service
public class DemoAgentService {

    private static final Logger log = LoggerFactory.getLogger(DemoAgentService.class);

    private final AgentRuntime agent;

    public DemoAgentService(ChatModel chatModel,
                            CompactingChatMemoryFactory memoryFactory,
                            AgentRuntimeFactory agentFactory,
                            BusinessTool businessTool,
                            BadCaseTool badCaseTool,
                            ToolMetaRegistry toolMetaRegistry) {
        // 注册 badcase 工具治理元数据：幂等性 + 重试次数 + 单调用超时
        registerBadCaseMetas(toolMetaRegistry);

        // 工具压缩策略：查询类工具完结即清
        ToolCompactionPolicyRegistry policyRegistry = ToolCompactionPolicyRegistry.withDefaults();
        policyRegistry.register(ToolCompactionRule.clearable("query_user_info"));
        policyRegistry.register(ToolCompactionRule.clearable("query_recent_orders"));

        // 压缩配置（M1 仅存模型名 + 预算参数）
        CompactConfig config = CompactConfig.ofModel("gpt-4o-mini");

        // 记忆
        CompactingChatMemory memory = memoryFactory.builder(chatModel)
                .config(config)
                .policyRegistry(policyRegistry)
                .build();

        // Agent
        this.agent = agentFactory.builder(chatModel)
                .agentName("DemoAgent")
                .agentVersion("1.0.0")
                .defaultSystem("""
                        你是我司的客服助手。你可以：
                        1. 查询用户信息
                        2. 查询用户的订单
                        3. 调用 badcase 工具做故障演练（flaky_idempotent_lookup / slow_timeout_tool /
                           terminal_bad_param / ambiguous_non_idempotent_charge）
                        请用友好、专业的语气回答用户问题。
                        """)
                .defaultOptions(OpenAiChatOptions.builder().temperature(0.7).build())
                .tools(businessTool, badCaseTool)
                .memory(memory)
                .listener(AgentLifecycleListener.onFinished(event -> {
                    log.info("session finished: agent={}, duration={}",
                            event.getSession().getAgentName(), event.getSession().getDuration());
                }))
                .build();
    }

    /** 注册 badcase 工具的治理元数据，供 GovernedToolCallback 重试/超时使用。 */
    private void registerBadCaseMetas(ToolMetaRegistry registry) {
        // 幂等 + 3 次尝试 + 固定 200ms 退避：验证 TRANSIENT 自动重试
        ToolMeta flaky = new ToolMeta();
        flaky.setToolName("flaky_idempotent_lookup");
        flaky.setIdempotent(true);
        flaky.setMaxAttempts(3);
        flaky.setBackoff(BackoffPolicy.fixed(200L));
        flaky.setTimeoutMs(10000L);
        registry.register(flaky);

        // 超时 1s，实际睡 3s：验证单调用超时 + 中断
        ToolMeta slow = new ToolMeta();
        slow.setToolName("slow_timeout_tool");
        slow.setIdempotent(true);
        slow.setMaxAttempts(1);
        slow.setTimeoutMs(1000L);
        registry.register(slow);

        // 幂等 + 3 次尝试，但抛 IllegalArgumentException（TERMINAL）：验证终态不重试
        ToolMeta terminal = new ToolMeta();
        terminal.setToolName("terminal_bad_param");
        terminal.setIdempotent(true);
        terminal.setMaxAttempts(3);
        terminal.setBackoff(BackoffPolicy.fixed(100L));
        registry.register(terminal);

        // 非幂等 + 3 次尝试，抛 SocketTimeoutException（AMBIGUOUS）：验证非幂等超时不重试
        ToolMeta ambiguous = new ToolMeta();
        ambiguous.setToolName("ambiguous_non_idempotent_charge");
        ambiguous.setIdempotent(false);
        ambiguous.setMaxAttempts(3);
        ambiguous.setBackoff(BackoffPolicy.fixed(100L));
        ambiguous.setTimeoutMs(5000L);
        registry.register(ambiguous);
    }

    /**
     * 发起对话。
     *
     * @param uid       用户 ID
     * @param sessionId  会话 ID
     * @param message    用户消息
     * @return 回复
     */
    public String chat(String uid, String sessionId, String message) {
        return agent.call(uid, sessionId, message);
    }

    /**
     * 发起流式对话，按 token 增量返回回复。
     *
     * @param uid       用户 ID
     * @param sessionId  会话 ID
     * @param message    用户消息
     * @return 回复 token 流
     */
    public Flux<String> chatStream(String uid, String sessionId, String message) {
        return agent.stream(uid, sessionId, message);
    }

    /**
     * 结束会话。
     *
     * @param sessionId 会话 ID
     */
    public void close(String sessionId) {
        agent.close(sessionId);
    }
}
