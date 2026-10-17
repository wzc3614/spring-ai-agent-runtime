# Agent Runtime 设计文档

> 版本：0.1（最小可用已跑通）
> 最后更新：2026-10-17（Spring AI 1.0.0 GA；A7 接入 ParallelToolCallingManager 并验证并行生效）

## 1. 项目定位

**AgentRuntime 是一个挂在 Spring AI 之上的 Harness 层**，聚焦"单 Agent 内部活得好、活得久、可复盘"，不重造 Spring AI，不跟编排层抢位置。落地形态是**嵌入式 Spring Boot Starter**：任意 Spring Boot + Spring AI 项目引入依赖即可用。

- 技术栈：Java 21 + Spring Boot 3.4 + Spring AI 1.0.0 GA
- 协议：Apache License 2.0
- groupId：`com.agentruntime`
- 基础设施全开源可插拔（PostgreSQL/MySQL、Redis、MinIO、Nacos/Apollo、OpenTelemetry）

## 2. 四条设计哲学

1. **叠加而非替代**：原样复用 Spring AI 的 `ChatClient`/`@Tool`/`ToolCallback`/`ChatMemory`/Observation，Harness 能力以 Advisor/装饰器/监听器方式挂上去。
2. **读写分离，把复杂度推后**：写入路径永远简单快速（毫秒级、零加工 append），压缩/预算决策推迟到注入模型前一刻。
3. **信息不丢弃，只降级**：面对上下文压力，从高精度原文渐进降级到高密度摘要，不是断崖截断。
4. **默认安全，业务无感**：异常清理、并发一致性、工具下线安全窗口、悬空调用修复由框架兜底。

## 3. 模块分层

```
api        契契约层（domain/enums/repository/spi/lifecycle/memory/spill/exception，零 Spring AI）
core       Spring AI 感知共享内核（parallel/safety/springai 基类/tool 元数据，不依赖子系统）
memory     记忆子系统（持久化 + 异步写入 + 微压缩 + 动态预算）
spill      溢出保护（MinIO/S3，暂缓）
observability 认知可观测（Span/Event 树 + 日志/JDBC Sink）
capability 能力供给（Skill + MCP 热插拔，暂缓）
hil        人工审批（暂缓）
starter    自动装配 + AgentRuntime 编排器
demo      端到端示例
```

依赖方向（无环）：`api ← core ← {memory, spill, observability, capability, hil}`；`starter → core + 各子系统`；`demo → starter`。`AgentRuntime` 编排器放在 starter（不是 core），避免 core 既是"底座"又是"编排器"的循环依赖。

## 4. 各模块设计

### 4.1 api（契约层）
- `domain`：9 个实体 POJO（`AgentSession`/`AgentMessage`/`SummarySnapshot`/`SummarySections`/`ToolCall`/`SpillRecord`/`ApprovalRecord`/`Span`/`EventRecord`），无参构造 + setter，JSON 字段统一 `String`，时间 `Instant`。
- `enums`：9 个枚举（`SessionStatusEnum`/`MessageRoleEnum`/`ToolCallStatusEnum`/`RiskLevelEnum`/`FailureCategoryEnum`/`ApprovalStatusEnum`/`SpanTypeEnum`/`EventTypeEnum`/`CompactionModeEnum`），`code`+`desc`+`of(code)`。注意 `MessageRoleEnum` 用小写 code（`system/user/assistant/tool`）以对齐 Spring AI `MessageType.getValue()`。
- `repository`：8 个数据访问端口（`SessionRepository` 含乐观锁、`MessageRepository`、`SummarySnapshotRepository`、`ToolCallRepository`、`SpillRecordRepository`、`ApprovalRepository`、`SpanRepository`、`EventRepository`）。
- `spi`：5 个能力端口（`SpillStorage`、`TokenEstimator`、`ToolFailureClassifier`、`ApprovalNotifier`、`TransactionPort`）。
- `lifecycle`：`AgentLifecycleEvent`/`SessionInfo`/`AgentLifecycleTypeEnum`（START/ENDED/CANCELLED/TIMED_OUT）/`AgentLifecycleListener`（含 `onFinished` 静态工厂）。
- `memory`：`CompactConfig`（`ofModel`）/`ToolCompactionRule`（`clearable`）/`ToolCompactionPolicyRegistry`（`withDefaults`）/`SessionSeqProvider`/`MessageWritePolicy`。
- `spill`：`SpillConfig`。
- `exception`：`AgentRuntimeException`/`OptimisticLockException`。

### 4.2 core（共享内核）
只依赖 api + Spring AI，**不依赖任何子系统**。放与具体后端无关的通用能力：
- `concurrency.parallel.ParallelToolExecutor`：虚拟线程 + Semaphore 限并发，结果按入参顺序对齐。
- `safety.dangling.DanglingToolCallDetector`：操作 Spring AI `Message` 列表，剔除缺响应的悬空 assistant tool_call（HIL 有意悬空通过 id 集合跳过）。
- `safety.lifecycle.SessionLifecycleCleaner`：实现 `AgentLifecycleListener`，终结事件分发注册的清理任务。
- `springai.BaseToolCallbackDecorator`：装饰器基类，委托 `ToolCallback`，供 Spill/Governed 复用。
- `springai.SpanContextHolder`：ThreadLocal 透传 TURN spanId + sessionId 给 `ObservingChatModel`/工具装饰器。
- `tool.ToolObjectResolver`：`@Tool` bean → `ToolCallback[]`（薄封装 `ToolCallbacks.from`）。
- `tool.ToolMeta`/`ToolMetaRegistry`/`DefaultToolMetaRegistry`：风险等级 + 幂等性元数据（默认保守：LOW + 非幂等）。
- `tool.DefaultToolFailureClassifier`：超时/IO→TRANSIENT，参数类→TERMINAL，非幂等+超时→AMBIGUOUS。

### 4.3 memory（记忆子系统）
**三层防御**：完整持久化 → 工具结果微压缩 → 九段式 LLM 摘要（M3）。

- 持久化（`persistence.jdbc`）：4 个 JDBC repo（`JdbcSessionRepository`乐观锁、`JdbcMessageRepository` `ON CONFLICT(session_id,seq) DO NOTHING` 幂等、`JdbcSummarySnapshotRepository`、`JdbcToolCallRepository`）；`TransactionPortImpl` 包装 `TransactionTemplate`。
- 异步写入（`write`）：两级异步。
  - 第一级：`CompactingChatMemory.add()` 把每条消息编码为 `StoredMessage` JSON 信封，经 `RedisSessionSeqProvider`（Redis INCR，DB MAX 初始化）分配 seq，组装 `WriteTask`（`taskId=sha256(kind|sessionID|turnID|messageSeq)` 幂等），经 `MessageWritePolicy.shouldWrite` 判定后入 `MemoryWriteQueue`（Redis FIFO）。
  - 第二级：`MemoryWriteWorker`（`@Scheduled`）扫描待写 session，加 Redis 分布式锁（TTL+Lua 释放），排空队列，`TransactionPort` 内批量 INSERT，失败重投，超限进 DLQ。
- 读取（`chat.CompactingChatMemory.get()`）：DB 历史 + Redis 队列 peek 合并（A1，解决 read-your-writes），按预算保留最近消息（`BudgetCalculator` + `LocalTokenEstimator`），解码为 Spring AI `Message`。
- 压缩（`compaction`）：`MicroCompactor`（纯内存，完结轮次工具结果→证据指针）、`BudgetCalculator`/`BudgetResult`。M1 超预算时保留最近 N 条 + warn（LLM 摘要 M3）。
- `chat.MessageConverter`/`StoredMessage`：Spring AI `Message` ↔ JSON 信封（含 `toolCalls`/`toolResponses`）跨轮回放。
- `chat.CompactingChatMemoryFactory`/`Builder`：工厂 bean（方案 A）持有基础设施，用户只设 `chatModel`/`config`/`policyRegistry`。

### 4.4 observability（认知可观测）
**分层**：捕获+日志永远在线，JDBC sink 条件装配，UI/OTLP 暂缓。

- `api.observability.ObservabilityRecorder`/`ObservabilitySink`：端口。
- `SpanTreeObservabilityRecorder`：建 Span/Event 树�，pending map + UUID；**缓冲 event，span 结束时先 writeSpan 再 writeEvent**（保证 event 的 span FK）。
- `LoggingObservabilitySink`：默认，永远在线，结构化写 slf4j（英文）。
- `JdbcObservabilitySink`：落库 `agent_runtime_obs`（条件装配 DataSource）。
- `JdbcSpanRepository`/`JdbcEventRepository`/`ObservabilityRowMappers`：span/event 仓储。
- `ObservingChatModel`：包 `ChatModel`，每次 LLM 调用建 `MODEL_CALL`（LlmCallSpan），从响应元数据采 `modelName/finishReason/inputTokens/outputTokens` 写 span 属性。
- `ObservabilityToolCallback`：包 `ToolCallback`（继承 `BaseToolCallbackDecorator`），每次调用建 `TOOL_CALL`（ToolCallSpan）+ `TOOL_INPUT`/`TOOL_OUTPUT` 事件，失败记 `ERROR`，span 属性带 `toolName`。
- 父 span 与 sessionId 经 `SpanContextHolder`（ThreadLocal）透传，仅适用同步调用路径。

### 4.5 spill / capability / hil
暂缓（见 TODO）。spill 设计为 MinIO/S3 溢出保护；capability 为 Skill + MCP 热插拔；hil 为人工审批复用悬空修复。

### 4.6 starter（编排器 + 自动装配）
- `runtime.AgentRuntime`：编排器入口，`builder(ChatModel)` 流式构建 `ChatClient`（挂 `MessageChatMemoryAdvisor`），`call(sessionId,userText)`/`close(sessionId)`，管 SESSION/TURN span + 生命周期事件 + duration。
- `runtime.AgentRuntimeBuilder`/`AgentRuntimeFactory`：工厂 bean（方案 A）。
- `autoconfigure.AgentRuntimeAutoConfiguration`：装配 JDBC repo、`TransactionPortImpl`、`LocalTokenEstimator`、`BudgetCalculator`、`ToolCompactionPolicyRegistry`、`MessageWritePolicy`、`MemoryWriteQueue`、`RedisSessionSeqProvider`、`MemoryWriteWorker`（`@EnableScheduling`）、`CompactingChatMemoryFactory`、`AgentRuntimeFactory`、observability 的 sink/recorder/jdbc 仓储。

### 4.7 demo
`BusinessTool`（`@Tool`：`query_user_info`/`query_recent_orders`）、`DemoAgentService`（用工厂组装 `CompactingChatMemory` + `AgentRuntime`）、`DemoController`（`POST /chat`）、`application.yml`（PG/Redis/OpenAI）、`application-local.yml`（阿里云 qwen 兼容网关）。

### 4.8 并行工具调用接入（ParallelToolCallingManager）
Spring AI 1.0.0 GA 的工具调用管理器只有 `DefaultToolCallingManager`（`public final`，顺序执行），**没有现成的并行实现**。Harness 自行实现 `ParallelToolCallingManager implements ToolCallingManager`，用 core 已有的 `ParallelToolExecutor`（虚拟线程 + Semaphore 限并发 + 结果按入参顺序对齐）并发执行一轮内多个独立 tool call，替换 Spring AI 默认管理器。

- 覆盖机制：GA 的 `ToolCallingAutoConfiguration`（FQCN `org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration`）注册的 `toolCallingManager` bean 带 `@ConditionalOnMissingBean`。Harness 的 `ParallelToolCallingAutoConfiguration` 加 `@AutoConfigureBefore(name = "...ToolCallingAutoConfiguration")` 并提供 `@Bean @ConditionalOnMissingBean ToolCallingManager`，先注册即生效，Spring AI 默认 bean 自动退让；用户可自定义 bean 覆盖退回串行。
- 观测不重写：`executeToolCalls` 调用的是 `options.getToolCallbacks()` 里已包装好的 `ObservabilityToolCallback`，并发下每个工具各自挂自己的 `TOOL_CALL` span 子树，`TOOL_INPUT`/`TOOL_OUTPUT` 天然仍生效。`SpanContextHolder` 的 ThreadLocal 值由虚拟线程继承，父 TURN spanId 透传不受影响。
- `resolveToolDefinitions` 复用 `ToolCallbackResolver`，逻辑与默认实现一致（从 `options.getToolCallbacks()` 取包装回调 + 按 `toolNames` 解析）。

## 5. 核心数据流

### 5.1 写入（异步、读写分离）
```
AgentRuntime.call → ChatClient(prompt+tools) → 模型回复
  ↓（MessageChatMemoryAdvisor.add）
CompactingChatMemory.add: 编码 JSON 信封 → Redis 分配 seq → 组装 WriteTask → MessageWritePolicy 判定 → MemoryWriteQueue(Redis FIFO)
  ↓（@Scheduled, 异步）
MemoryWriteWorker: 加分布式锁 → 排空队列 → TransactionPort 批量 INSERT（ON CONFLICT 幂等）→ 失败重投/超限 DLQ
```

### 5.2 读取（注入前）
```
CompactingChatMemory.get: DB 历史 + Redis 队列 peek 合并（A1）→ 按预算保留最近 N（BudgetCalculator + LocalTokenEstimator）→ MessageConverter 解码为 Spring AI Message
  ↓
MessageChatMemoryAdvisor 注入 → 模型
```

### 5.3 观测
```
AgentRuntime.call
  ├─ ensureStarted: startSpan(SESSION)
  ├─ startSpan(TURN) + SpanContextHolder.set(turnSpanId)
  │   ├─ ChatClient(prompt.tools(wrapped)).call()
  │   │   ├─ ObservingChatModel.call: startSpan(MODEL_CALL) → 采 usage/finishReason → endSpan
  │   │   └─ 工具执行: ObservabilityToolCallback.call: startSpan(TOOL_CALL) + TOOL_INPUT/OUTPUT → endSpan
  │   └─ recordEvent(TURN, FINAL_REPLY)
  └─ close: endSpan(SESSION) + 生命周期 ENDED 事件 + duration
```
落库 span 树：`SESSION → TURN → {MODEL_CALL, TOOL_CALL...}`，event：`FINAL_REPLY/TOOL_INPUT/TOOL_OUTPUT/ERROR`。

## 6. 数据模型

8 张 `agent_*` 表（public schema）+ `agent_runtime_obs.span`/`event_record`（独立 schema）。Flyway 迁移：V1 init schema、V2 span attributes、V3 event payload jsonb→text。命名统一 `snake_case` + `agent_` 前缀。

## 7. 路线图

- M1 记忆与基础可观测 ✅（最小可用已跑通）
- M2 Spill + 能力供给（暂缓）
- M3 LLM 摘要 + 并行调用 + 悬空修复（并行调用已完成：A7 ParallelToolCallingManager 已接入并验证）
- M4 人工审批（暂缓）
- M5 可观测平台化（UI + OTLP，暂缓）

详见 `docs/TODO.md`。
