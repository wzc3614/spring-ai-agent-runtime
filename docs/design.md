# Agent Runtime 设计文档

> 版本：0.1（最小可用已跑通）
> 最后更新：2026-10-25（轮次执行：断线回放与崩溃整轮重跑）
> 最后更新：2026-10-25（单轮调用树页面；单轮 ReAct 步数 / Token 预算；修正摘要、调用签名、观测与写入路径中的过时描述）
> 最后更新：2026-10-23（移除 HIL 人工审批模块）
> 最后更新：2026-10-17（Spring AI 1.0.0 GA；A7 接入 ParallelToolCallingManager 并验证并行生效）

## 1. 项目定位

**AgentRuntime 是一个挂在 Spring AI 之上的 Harness 层**，聚焦"单 Agent 内部活得好、活得久、可复盘"，不重造 Spring AI，不跟编排层抢位置。落地形态是**嵌入式 Spring Boot Starter**：任意 Spring Boot + Spring AI 项目引入依赖即可用。

- 技术栈：Java 21 + Spring Boot 3.4 + Spring AI 1.0.0 GA
- 协议：Apache License 2.0
- groupId：`com.agentruntime`
- 当前基础设施：PostgreSQL、Redis。MySQL 适配、Nacos/Apollo、OpenTelemetry 导出尚未接入

## 2. 四条设计哲学

1. **叠加而非替代**：原样复用 Spring AI 的 `ChatClient`/`@Tool`/`ToolCallback`/`ChatMemory`/Observation，Harness 能力以 Advisor/装饰器/监听器方式挂上去。
2. **读写分离，把复杂度推后**：写入路径永远简单快速（毫秒级、零加工 append），压缩/预算决策推迟到注入模型前一刻。
3. **信息不丢弃，只降级**：面对上下文压力，从高精度原文渐进降级到高密度摘要，不是断崖截断。
4. **默认安全，业务无感**：写入崩溃不丢消息、序号冲突重分配、工具失败分类由框架兜底。悬空调用检测类已实现，尚未接入读历史路径。

## 3. 模块分层

```
api        契约层（domain/enums/repository/spi/lifecycle/memory/budget/exception，零 Spring AI）
core       Spring AI 感知共享内核（parallel/safety/budget/springai 基类/tool 元数据，不依赖子系统）
memory     记忆子系统（持久化 + 异步写入 + 工具结果压缩 + 记忆窗口预算）
observability 认知可观测（Span/Event 树 + 日志/JDBC Sink）
capability 能力供给（Skill + MCP 热插拔，MCP 未做）
starter    自动装配 + AgentRuntime 编排器
demo       端到端示例
```

独立 spill 模块已删除。工具原文在 `agent_message.payload`，不再走对象存储。

依赖方向（无环）：`api ← core ← {memory, observability, capability}`；`starter → core + 各子系统`；`demo → starter`。`AgentRuntime` 编排器放在 starter（不是 core），避免 core 既是"底座"又是"编排器"的循环依赖。

## 4. 各模块设计

### 4.1 api（契约层）
- `domain`：实体 POJO（`AgentSession`/`AgentMessage`/`SummarySnapshot`/`SummaryContent`/`ToolCall`/`Span`/`EventRecord`），无参构造 + setter，JSON 字段统一 `String`，时间 `Instant`。工具原文在 `AgentMessage.payload`。
- `enums`：状态 / 角色 / 风险 / 失败 / span / event / 压缩策略枚举。
- `repository`：数据访问端口（`SessionRepository` 含乐观锁、`MessageRepository`、`SummarySnapshotRepository`、`ToolCallRepository`、`SpanRepository`、`EventRepository`）。
- `spi`：`TokenEstimator`、`ToolFailureClassifier`、`TransactionPort`、`ToolResultCompactor`。
- `lifecycle`：`AgentLifecycleEvent`/`SessionInfo`/`AgentLifecycleTypeEnum`（START/ENDED/CANCELLED/TIMED_OUT）/`AgentLifecycleListener`（含 `onFinished` 静态工厂）。
- `memory`：`CompactConfig`（记忆窗口预算，不是单轮 ReAct 消耗）、`ToolCompactionRule`（`query` / `retain` / `summarize` / `custom`）、`ToolCompactionPolicyRegistry`（默认查询类）、`SessionSeqProvider`、`MessageWritePolicy`、`ChatHistorySummarizer`。
- `budget`：`TurnBudget` / `TurnBudgetConstants`。限制一次用户请求内的模型调用次数和累计 token，与 `CompactConfig` 分开。
- `exception`：`AgentRuntimeException`/`OptimisticLockException`。

### 4.2 core（共享内核）
只依赖 api + Spring AI，**不依赖任何子系统**。放与具体后端无关的通用能力：
- `concurrency.parallel.ParallelToolExecutor`：虚拟线程 + Semaphore 限并发，结果按入参顺序对齐。
- `budget.BoundedToolCallingChatModel`：单轮 ReAct 循环。关闭下游 `internalToolExecutionEnabled`，自己按步调用模型和 `ToolCallingManager`。默认最多 6 次模型调用、累计 64000 token。触顶后若这一步仍要工具，不再执行、也不再请求模型。已经给出最终文本的那一步照常返回。
- `safety.dangling.DanglingToolCallDetector`：能剔除缺响应的悬空 assistant tool_call。类已存在，**尚未接到读历史路径**。
- `safety.lifecycle.SessionLifecycleCleaner`：实现 `AgentLifecycleListener`。类已存在，**尚未注册为监听器**。
- `springai.BaseToolCallbackDecorator`：装饰器基类，委托 `ToolCallback`，供 Governed 复用。
- `springai.SpanContextHolder`：同步路径用 `InheritableThreadLocal`（虚拟线程可继承）；流式路径经 context-propagation 在 reactor 线程恢复 TURN spanId + sessionId。
- `tool.ToolObjectResolver`：`@Tool` bean → `ToolCallback[]`（薄封装 `ToolCallbacks.from`）。
- `tool.ToolMeta`/`ToolMetaRegistry`/`DefaultToolMetaRegistry`：风险等级 + 幂等性元数据（默认保守：LOW + 非幂等）。
- `tool.DefaultToolFailureClassifier`：超时/IO→TRANSIENT，参数类→TERMINAL，非幂等+超时→AMBIGUOUS。

### 4.3 memory（记忆子系统）
**三层防御**：完整持久化 → 工具结果按策略降级 → 异步摘要。摘要不是一次生成的九段式，而是可选结构化抽取，再由 `ChatHistorySummarizer` 增量更新 `SummaryContent`（schemaVersion=2：Goal / Constraints / Progress / Decisions / Facts / Open Questions / Next Steps / Provenance）。失败不覆盖旧快照。

这里的「预算」是记忆窗口：`CompactConfig` + `BudgetCalculator` 决定注入模型前保留多少历史。它不限制本轮 ReAct 能走多少步、花多少 token。后者见 `TurnBudget`。

- 持久化（`persistence.jdbc`）：4 个 JDBC repo（`JdbcSessionRepository`乐观锁、`JdbcMessageRepository` `ON CONFLICT(session_id,seq) DO NOTHING` 幂等、`JdbcSummarySnapshotRepository`、`JdbcToolCallRepository`）；`TransactionPortImpl` 包装 `TransactionTemplate`。
- 异步写入（`write`）：两级异步。
  - 第一级：`CompactingChatMemory.add()` 把每条消息编码为 `StoredMessage` JSON 信封，经 `RedisSessionSeqProvider` 分配 seq。序号键丢失时，水位取数据库最大 seq 和队列未落库 seq 的较大值，再 INCR。组装 `WriteTask`（`taskId=sha256(kind|sessionID|turnID|messageSeq)` 幂等），经 `MessageWritePolicy.shouldWrite` 判定后入 `MemoryWriteQueue`。
  - 第二级：`MemoryWriteWorker`（`@Scheduled`）扫描待写 session，加 Redis 分布式锁（TTL+Lua 释放）。认领用 `LMOVE` 把消息搬到处理中列表，数据库提交成功才删除；进程死在提交前，消息留在处理中列表。同一 `messageId` 的 seq 冲突视为重试；不同 `messageId` 撞上 `ON CONFLICT DO NOTHING` 时重新分配 seq。失败重投，超限进 DLQ。需要 Redis 6.2+。
- 读取（`chat.CompactingChatMemory.get()`）：先取可用摘要边界，只加载边界之后的 DB 行，再与 Redis 队列合并（解决 read-your-writes，同一 seq 以数据库行为准）。按记忆窗口预算保留尾部（`BudgetCalculator` + `LocalTokenEstimator`），摘要以 `CompactionMessage` 注入。解码为 Spring AI `Message`。
- 压缩（`compaction`）：`ToolResultRenderer` 按工具策略生成上下文视图（查询过期 / 操作保留 / 搜索摘要），原文在 `agent_message.payload`。会话摘要由 `SummaryCompactor` 异步触发：加锁并续期 → 按边界加载尾部 → `TurnSelector` → 可选 `StructuredExtractor` → `ChatHistorySummarizer`。
- `chat.MessageConverter`/`StoredMessage`：Spring AI `Message` ↔ JSON 信封（含 `toolCalls`/`toolResponses`）跨轮回放。
- `chat.CompactingChatMemoryFactory`/`Builder`：工厂 bean（方案 A）持有基础设施，用户只设 `chatModel`/`config`/`policyRegistry`。

### 4.4 observability（认知可观测）
**分层**：捕获+日志永远在线，JDBC sink 条件装配。Starter 提供默认关闭的只读文本树。完整 UI 与 OTLP 仍未做。

- `api.observability.ObservabilityRecorder`/`ObservabilitySink`：端口。
- `SpanTreeObservabilityRecorder`：建 Span/Event 树，pending map + UUID；**缓冲 event，span 结束时先 writeSpan 再 writeEvent**（保证 event 的 span FK）。
- `LoggingObservabilitySink`：默认，永远在线，结构化写 slf4j（英文）。
- `JdbcObservabilitySink`：同步落库 `agent_runtime_obs`（条件装配 DataSource，`mode=sync` 时启用）。
- `RedisStreamObservabilitySink` + `ObservabilityStreamQueue` + `ObservabilityStreamConsumer`：`mode=async`（默认）异步通道，span/event 经 Redis Stream + 消费组 XADD/XREADGROUP/XACK 异步落库，失败重投 + DLQ，XCLAIM 接管宕机 consumer；FK 顺序靠 EVENT 落库前 `findById` 校验 + 重投兜底，DB `ON CONFLICT DO NOTHING` 保证幂等。
- `JdbcSpanRepository`/`JdbcEventRepository`/`ObservabilityRowMappers`：span/event 仓储。
- `ObservingChatModel`：包在 `BoundedToolCallingChatModel` 里面。循环的每一次模型调用各建一个 `MODEL_CALL`，从该次响应采集 `modelName/finishReason/inputTokens/outputTokens`。主模型才记 `THINKING`。路由模型和摘要模型只记调用，不记推理。它看不到、也不限制整轮累计消耗。优先用响应里的 `reasoning_content`（Spring AI 1.0.0 不映射该字段，由 OpenAI 兼容客户端的同步/流式拦截器捡回）。没有这段、但回复同时带工具调用时，用工具旁边的正文。纯最终回答不记成思考过程。
- `ObservabilityToolCallback`：包 `ToolCallback`（继承 `BaseToolCallbackDecorator`），每次调用建 `TOOL_CALL`（ToolCallSpan）+ `TOOL_INPUT`/`TOOL_OUTPUT` 事件，失败记 `ERROR`，span 属性带 `toolName`。
- 父 span 与 sessionId 经 `SpanContextHolder` 透传。同步调用靠 `InheritableThreadLocal`；流式调用靠 context-propagation，reactor 线程上同样能建 span。
- `trace.TraceTreeRenderer`：纯函数。输入会话、该会话的 span、每个 span 的 event。只展开一个 TURN 及其子孙，子节点按 `startedAt` 排序。`MODEL_CALL` 在这棵子树里按前序编号。展示模型名、结束原因、token、工具名、`TOOL_INPUT` / `TOOL_OUTPUT`、耗时、`ERROR`、`TURN_BUDGET_EXCEEDED`。工具入出参超过 200 字截断。`MODEL_CALL` 上的 `THINKING` 显示为 `thinking`，`TURN` 上的 `FINAL_REPLY` 显示为 `response`，这两行保留换行，超过 800 字截断。思考过程与最终回复正文相同时只保留 `response`。不展示费用。父 span 为空的压缩 span 不挂进这棵树。

### 4.5 工具结果压缩（并入 memory）
工具原文存在 `agent_message.payload`（JSONB），不再使用对象存储。读路径按工具策略生成上下文视图：查询类过期后删除原文并留下可重新调用的短句，操作类完整保留并在会话摘要之后回捞，搜索类在当前轮之后换成确定性摘要。未注册工具默认按查询类过期。自定义策略实现 `ToolResultCompactor`。

### 4.6 starter（编排器 + 自动装配）
- `runtime.AgentRuntime`：编排器入口。`call(uid, sessionId, userText)` / `stream(uid, sessionId, userText)` / `streamTurn(uid, sessionId, userText)` / `close(sessionId)`。`uid` 是用户，`sessionId` 是会话生命周期和记忆作用域。管 SESSION/TURN span、生命周期事件和 duration。轮次执行开启时，`streamTurn` 返回带 `turnId` 的帧流，`stream` 仍返回这段帧里的回答文本。
- `runtime.AgentRuntimeBuilder`：装饰顺序是 `BoundedToolCallingChatModel` → `ObservingChatModel` → 原始 `ChatModel`，再挂 `MessageChatMemoryAdvisor`。单轮预算来自 `agent.runtime.turn-budget.max-model-calls`（默认 6）和 `max-turn-tokens`（默认 64000）。供应商未回 usage 时用 `TokenEstimator` 估算。触顶且仍要工具时返回固定回复，并在 TURN span 上记 `TURN_BUDGET_EXCEEDED`（`STEP_LIMIT` 或 `TOKEN_LIMIT`）。
- `runtime.AgentRuntimeFactory`：工厂 bean，持有 `ToolCallingManager` 和 `TurnBudget`。
- `autoconfigure.AgentRuntimeAutoConfiguration`：装配 JDBC repo、`TransactionPortImpl`、`LocalTokenEstimator`、`BudgetCalculator`、`ToolCompactionPolicyRegistry`、`MessageWritePolicy`、`MemoryWriteQueue`、`RedisSessionSeqProvider`、`MemoryWriteWorker`（`@EnableScheduling`）、`CompactingChatMemoryFactory`、`AgentRuntimeFactory`、observability 的 sink/recorder/jdbc 仓储。`TurnBudgetProperties` 在这里绑定。
- `autoconfigure.TraceAutoConfiguration`：只读页面 `GET /agent-runtime/trace?sessionId=&turn=`。`agent.runtime.trace.enabled=true` 才注册，还要求 Web 应用和三个仓储都在。`spring-boot-starter-web` 是 starter 的 optional 依赖，不会传递给非 Web 接入方。未指定 `turn` 时展示该会话最近一轮，页面上保留其它轮的链接。演示时把 `agent-runtime.observability.persist.mode` 设为 `sync`，避免异步落库还没写完就打开页面。

### 4.7 demo
`BusinessTool`（`@Tool`：`query_user_info`/`query_recent_orders`）、`DemoAgentService`（用工厂组装 `CompactingChatMemory` + `AgentRuntime`）、`DemoController`（`POST /chat`）、`application.yml`（PG/Redis/OpenAI）、`application-local.yml`（阿里云 qwen 兼容网关）。

### 4.8 并行工具调用接入（ParallelToolCallingManager）
Spring AI 1.0.0 GA 的工具调用管理器只有 `DefaultToolCallingManager`（`public final`，顺序执行），**没有现成的并行实现**。它的 `OpenAiChatModel.internalCall` 还会在模型内部递归执行工具，且不限制步数。Harness 做两件事：用 `BoundedToolCallingChatModel` 关掉 `internalToolExecutionEnabled`，把循环拿出来；用 `ParallelToolCallingManager` 执行同一步里的多个 tool call。

- 覆盖机制：GA 的 `ToolCallingAutoConfiguration`（FQCN `org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration`）注册的 `toolCallingManager` bean 带 `@ConditionalOnMissingBean`。Harness 的 `ParallelToolCallingAutoConfiguration` 加 `@AutoConfigureBefore(name = "...ToolCallingAutoConfiguration")` 并提供 `@Bean @ConditionalOnMissingBean ToolCallingManager`，先注册即生效，Spring AI 默认 bean 自动退让；用户可自定义 bean 覆盖退回串行。
- 观测不重写：`executeToolCalls` 调用的是 `options.getToolCallbacks()` 里已包装好的 `ObservabilityToolCallback`，并发下每个工具各自挂自己的 `TOOL_CALL` span 子树，`TOOL_INPUT`/`TOOL_OUTPUT` 天然仍生效。`SpanContextHolder` 的 ThreadLocal 值由虚拟线程继承，父 TURN spanId 透传不受影响。
- `resolveToolDefinitions` 复用 `ToolCallbackResolver`，逻辑与默认实现一致（从 `options.getToolCallbacks()` 取包装回调 + 按 `toolNames` 解析）。

### 4.9 轮次执行（断线回放与崩溃重跑）

观测 span 负责事后复盘，`CompactingChatMemory` 负责进模型的权威历史。这两条都不提供「客户端从某个序号续上」或「进程死了把这一轮跑完」。轮次执行补的是执行协议，不另建一套会话原文，也不把摘要再做一遍。

开关是 `agent.runtime.turn-execution.enabled`（默认开）。关掉时 `stream` 保持原来的 `Flux<String>`，执行跟着订阅者取消。打开时：

- `streamTurn` 返回 `TurnHandle`：`turnId`、是否复用了未完成轮、以及帧流。帧类型只有 `ANSWER` / `DONE` / `ERROR` / `TURN_RESTARTED`。
- `stream` 仍返回文本，内容是这些帧里的 `ANSWER`。取消这个文本流，等同于取消一次回放订阅。
- `call` 碰到同一会话已有未完成轮时抛 `TurnBusyException`，异常上带已有 `turnId`，调用方改走回放。同步调用不在别人的轮次后面空等。
- HTTP 适配把 `turnId` 放在响应头 `X-Turn-Id`，`X-Turn-Reused` 表示这次有没有复用。可选回放端点 `GET /agent-runtime/sessions/{sessionId}/turns/{turnId}/events?afterSeq=&uid=`，有 Web 才注册。

三条状态继续分开：

| 状态 | 落点 | 职责 |
|---|---|---|
| 会话 | 现有 `agent_session` | 用户与会话归属。`uid` 与 `user_id` 不一致则拒绝 |
| 轮次 | Redis 哈希 + `agent_turn` | `ACCEPTED → RUNNING → COMPLETED / FAILED / CANCELLED`。租约只在 Redis |
| 记忆 | 现有 `CompactingChatMemory` | 权威历史。本轮消息写入同一个执行 `turnId` |
| 回放 | Redis Stream `agentruntime:turn:{turnId}:events` | 单调 `seq`，给客户端续传。默认保留 24 小时 |
| 观测 | 现有 span / event | 不参与 `afterSeq` |

同一会话只允许一个未完成轮。`begin` 在 Redis 里原子占住 `agentruntime:turn:active:{sessionId}`。撞上未完成轮时返回原 `turnId`，不追加第二条 user，也不再调模型。

断线与执行拆开。模型调用跑在已有的虚拟线程执行器上，SSE 断开只结束这一次订阅。回答按条数或时间批量写入事件流，避免一个 token 一次 Redis。默认 `cancel-on-client-disconnect=false`：没有订阅者之后这一轮仍会跑完。打开该开关后，最后一个订阅者离开就把轮次标为 `CANCELLED`，并写一条 `ERROR`（`CLIENT_DISCONNECTED`）。这个默认会在用户关掉页面后继续消耗模型额度。

崩溃后续跑整轮，不从半个 token 接着写。

- 执行中的实例按 `renew-interval` 续租约，`BoundedToolCallingChatModel` 每一步开始时再续一次。续约和写事件都校验 `leaseOwner`，对不上就停，并且不再把这一轮标成完成。
- 周期扫描本 agent 名下租约已过期的 `RUNNING`，以及创建时间已经超过一个租约 TTL 的 `ACCEPTED`（盖住「刚占住轮次就死了」的窗口，又不会抢走刚创建、还没标成 `RUNNING` 的轮）。CAS 认领成功后清空旧事件，先写 `TURN_RESTARTED`。
- 认领后删掉这一轮已经写入记忆的消息（`agent_message.turn_id`，含队列里还没落库的），再按原 query 重跑。`MessageChatMemoryAdvisor` 是先 `get` 再把当前 user 拼进 prompt，所以重跑期间 `get` 会藏起当前 `turnId` 的消息，避免同一句 user 进两次模型。
- 工具一开始执行就把幂等性记到 Redis。存在非幂等工具已开始时不重跑，轮次记 `FAILED`，错误码 `NON_IDEMPOTENT_PARTIAL`。默认工具元数据是非幂等，所以默认是失败关闭。
- 没有 Redis 时不要打开恢复。`crash-recovery-enabled` 默认开，前提是本就依赖的 Redis 还在。

`call` / `stream` 关掉轮次执行时，`CompactingChatMemory.add()` 仍为每次写入生成自己的 `turnId`，和现在一样。

## 5. 核心数据流

### 5.1 写入（异步、读写分离）
```
AgentRuntime.call/stream → ChatClient(prompt+tools) → 模型回复
  ↓（MessageChatMemoryAdvisor.add）
CompactingChatMemory.add: 编码 JSON 信封 → Redis 分配 seq（水位 = max(DB, 未落库队列)）→ 组装 WriteTask（轮次执行开启时 turnId 用编排器这一轮的，并写入 agent_message.turn_id）→ MessageWritePolicy 判定 → MemoryWriteQueue
  ↓（@Scheduled, 异步）
MemoryWriteWorker: 加分布式锁 → LMOVE 认领到处理中列表 → 提交成功后删除 → 同一 messageId 幂等，不同 messageId 撞 seq 则重分配 → 失败重投/超限 DLQ
```

### 5.2 读取（注入前）
```
CompactingChatMemory.get: 摘要边界之后的 DB 行 + Redis 队列合并（同一 seq 以数据库为准）→ 记忆窗口预算裁剪尾部 → 注入 CompactionMessage → MessageConverter 解码为 Spring AI Message
  ↓
MessageChatMemoryAdvisor 注入 → 模型
```

### 5.3 观测
```
AgentRuntime.call / stream
  ├─ ensureStarted: startSpan(SESSION)
  ├─ startSpan(TURN) + SpanContextHolder.set(turnSpanId)   # stream 再写入 Reactor Context
  │   └─ BoundedToolCallingChatModel（internalToolExecutionEnabled=false）
  │        循环，直到没有工具调用，或步数 / 累计 token 触顶：
  │        ├─ ObservingChatModel：一次模型调用一个 MODEL_CALL
  │        ├─ 仍要工具且未触顶：ParallelToolCallingManager → ObservabilityToolCallback（TOOL_CALL）
  │        └─ 触顶且仍要工具：固定回复 + TURN_BUDGET_EXCEEDED，不再调用模型
  │   └─ recordEvent(TURN, FINAL_REPLY)
  └─ close: endSpan(SESSION) + 生命周期 ENDED 事件 + duration
```
落库 span 树：`SESSION → TURN → {MODEL_CALL, TOOL_CALL...}`。一次用户请求里可以有多个 `MODEL_CALL`，每个对应一步。event 含 `FINAL_REPLY` / `TOOL_INPUT` / `TOOL_OUTPUT` / `ERROR` / `TURN_BUDGET_EXCEEDED` / `COMPACTION_FAILED`，以及 Skill、轮次钩子相关事件。

打开 `agent.runtime.trace.enabled=true` 后，`TraceTreeService` 读这棵已落库的树，`TraceTreeRenderer` 画成文本，`TraceController` 包进 HTML。页面只读，不新写表。

### 5.4 轮次执行
```
streamTurn / call
  ├─ ensureStarted
  ├─ begin（单飞；已有未完成轮则复用或 call 抛 TurnBusyException）
  ├─ markRunning + leaseOwner
  ├─ 虚拟线程执行模型调用（与 SSE 订阅者分离）
  │    ├─ 批量 ANSWER → Redis Stream
  │    ├─ 步边界与心跳续租约
  │    └─ 非幂等工具开始即记 unsafe
  ├─ COMPLETED + DONE，或 FAILED / CANCELLED + ERROR
  └─ 客户端 afterSeq 跟随；进程重启后扫描过期租约，安全时整轮重跑
```

## 6. 数据模型

业务表在 public schema（session / message / summary_snapshot / tool_call / turn，外加 skill）。工具原文在 `agent_message.payload`。`agent_message.turn_id` 标记这一轮执行写下的消息，重跑前按它删除。`agent_spill_record` 由 V7 删除。观测表在 `agent_runtime_obs`。轮次租约和回放事件在 Redis，不进观测表。命名统一 `snake_case` + `agent_` 前缀。

## 7. 路线图

- M1 记忆与基础可观测 ✅（最小可用已跑通）
- M2 工具结果压缩（已并入 memory）+ 能力供给中的 MCP（未做）
- M3 摘要 + 并行调用 + 悬空修复。摘要已落地为结构化抽取 + 增量 rollup（不是九段式）。并行调用已接入，并由单轮步数 / token 预算包住。悬空检测类已有，尚未接入读路径
- M5 可观测平台化。单轮文本树页面已由 Starter 提供，默认关闭。完整 UI 与 OTLP 仍未做
- M6 轮次执行。断线回放与崩溃整轮重跑已接入编排器。工具结果检查点、从 ReAct 中途续跑仍未做

详见 `docs/TODO.md`。
