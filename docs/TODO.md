# Agent Runtime 待办与状态

> 最后更新：2026-10-23（移除 HIL 人工审批模块及其契约、表和待办）
> 最后更新：2026-10-23（基础版 LLM 摘要记忆落地：异步压缩 + Compaction 角色 + ChatHistorySummarizer SPI；放弃九段式）
> 最后更新：2026-10-17（端到端全通：工具调用+观测 span 树完整；修 4 个 bug）
> 最后更新：2026-10-17（Spring AI 1.0.0 GA；A7 接入 ParallelToolCallingManager 并 demo 验证并行生效）
> 状态图例：`[ ]` 待办 / `[~]` 进行中 / `[x]` 完成 / `[-]` 暂缓

## 已完成（基线）

- [x] 项目骨架：parent pom + 7 模块 + demo + Flyway V1 schema + LICENSE(Apache 2.0)
- [x] `api` 模块：domain(8) / enums(8) / repository(7) / spi(4) / lifecycle(3) / memory 配置(3) / spill 配置(1) / exception(2)
- [x] `memory` 模块：4 个 JDBC repo、TransactionPortImpl、LocalTokenEstimator、BudgetCalculator、MicroCompactor、异步写入路径（WriteTask/Queue/Worker/SeqProvider/Policy）
- [x] `starter`：AgentRuntime + Builder + Factory、CompactingChatMemory + Builder + Factory、MessageConverter、AgentRuntimeAutoConfiguration
- [x] `demo`：BusinessTool / DemoAgentService / DemoController / Application / application.yml
- [x] 最小可用端到端编译 + 打包通过
- [x] Spring AI 1.0.0-M6 → 1.0.0 GA 升级（7 处代码适配 + 3 个 pom 调整，已端到端验证：工具调用 / span 树 / 记忆落库全通）

## 待建任务

### A. Core 共享内核（当前空模块）
- [x] A1 `ParallelToolExecutor` — 虚拟线程并发执行工具 + 结果顺序对齐
- [x] A2 `DanglingToolCallDetector` — 加载历史时检测/修复悬空 tool_call
- [x] A3 `SessionLifecycleCleaner` — 会话结束协调各子系统清理
- [x] A4 `BaseToolCallbackDecorator` + `ToolObjectResolver` — 装饰器基类 + `@Tool`→`ToolCallback`
- [x] A5 工具元数据注册表（risk level + idempotent）+ `DefaultToolFailureClassifier`
- [-] A6 `AgentSessionClient`/`CallResponse`/`PromptSpec`/`StreamResponse` + `ChatRequestOptions`（暂缓，编排器稳定后再抽）
- [x] A7 接入 `ParallelToolCallingManager` — 自实现 `ToolCallingManager`，用 `ParallelToolExecutor` 并发执行一轮内多个 tool call，经 `ParallelToolCallingAutoConfiguration`（`@AutoConfigureBefore` Spring AI 的 `ToolCallingAutoConfiguration`）替换默认 `DefaultToolCallingManager`；观测装饰器链保持不变。附：`SpanContextHolder` 改 `InheritableThreadLocal` 以让虚拟线程继承 TURN spanId/sessionId（已 demo 验证：两个 TOOL_CALL span 起止时间完全重叠，并行生效）

### B. Memory 补齐
- [ ] B1 把 `MicroCompactor` 接进 `CompactingChatMemory.get()`
- [x] B2 基础版 LLM 摘要（放弃九段式）：历史超预算时异步调 `ChatModel` 压缩为一段摘要，存 `agent_summary_snapshot`（`sections` 通用 JSON + `summarized_up_to_seq` 边界，V4 迁移）；读侧注入 `CompactionMessage`（新 `MessageRoleEnum.COMPACTION`，借 `MessageType.SYSTEM`）；`ChatHistorySummarizer` SPI 供用户自定义；`MemoryBudgetContextHolder` 由 `AgentRuntime` 写入 fixedOverhead；per-session Redis 锁 + double-check，失败不重试
- [ ] B3 异步写入路径实测验证（worker 排空 / Redis seq 初始化 / A1 读侧合并）
- [ ] B4 `MessageWritePolicy` 用户扩展示例/文档
- [ ] B5 摘要路径实测验证（长对话触发压缩 / 下一轮注入 summary / 并发不重复压缩 / 失败不重试）

### C. Spill（M2，未建）
- [ ] C1 `SpillGuardToolCallback`
- [ ] C2 MinIO `SpillStorage` 实现
- [ ] C3 `JdbcSpillRecordRepository`
- [ ] C4 回读（字节区间 / JSONPath / 分页游标）
- [ ] C5 把 Spill 接进 `AgentRuntime`

### D. Observability（M1 埋点 + M5 平台化，未建）
- [x] D1 Span/Event 树构建器（`SpanTreeObservabilityRecorder` + `ObservabilityRecorder`/`ObservabilitySink` SPI）
- [x] D2 `JdbcSpanRepository` / `JdbcEventRepository`（+ RowMappers）
- [x] D3 基础埋点接进调用链（SESSION/TURN + FINAL_REPLY；LlmCallSpan via ObservingChatModel 记 usage/finishReason；ToolCallSpan via ObservabilityToolCallback 记 TOOL_INPUT/OUTPUT；span 加 attributes）
- [x] D3.1 日志异步写入：Redis Stream + 消费组（`RedisStreamObservabilitySink`/`ObservabilityStreamQueue`/`ObservabilityStreamConsumer`），`mode=async` 默认；失败重投 + DLQ + XCLAIM 接管；FK 顺序靠 `findById` 校验 + 重投 + `ON CONFLICT DO NOTHING` 幂等
- [ ] D4 OpenTelemetry/OTLP 导出
- [ ] D5 自建轻量可视化 Web UI
- [ ] D6 查询 + ASCII 树渲染器 + REST（本期不做）

### E. Capability（M2，未建）
- [ ] E1 Skill 加载（classpath + DB 动态，DB 覆盖同名）
- [ ] E2 MCP/工具热插拔（Nacos 默认 + 降级）
- [ ] E3 差量刷新 + 引用计数延迟关闭
- [ ] E4 `DynamicToolCallbackProvider`
- [ ] E5 内置原子工具

### G. 工具失败/重试（已设计未建）
- [x] G1 `GovernedToolCallback`/`RetryToolCallback` — 已落地为 `GovernedToolCallback`（重试+分类装饰器），接进 `AgentRuntimeBuilder` 包装链（raw -> Governed -> Observability）
- [x] G2 `DefaultToolFailureClassifier`（依赖 A5）— 已有，本轮接入调用链
- [x] G3 单调用超时 + 失败隔离 — `ParallelToolExecutor` 增按任务超时重载（`future.get(timeout)` + `cancel(true)`），返回 `TaskResult`；`ParallelToolCallingManager` 按工具元数据取超时、单工具失败/超时转错误结果回填模型不阻塞链路；`ToolMeta` 扩展 `timeoutMs/maxAttempts/backoff`，`ToolGovernanceProperties` 提供全局默认（默认不重试、30s 超时）

### H. 横切 / 基建 / 质量
- [ ] H1 单元测试 + 集成测试
- [x] H2 实跑 demo 端到端验证（启动/Flyway/记忆/观测 span+event/工具调用 全通；修 4 个 bug：MessageRoleEnum 大小写、event payload jsonb→text、event FK 缓冲、ChatOptions→OpenAiChatOptions）
- [ ] H3 README / 使用文档 / 开源说明
- [ ] H4 `@ConfigurationProperties` 调优配置
- [ ] H5 MySQL 适配验证
- [-] H6 Kafka/RocketMQ 异步（暂缓，Redis 队列先行）
- [-] H7 长期记忆 pgvector/Milvus（M6 远期）

### I. 编排原语层（进阶，暂缓 — 详见 `docs/orchestration-design.md` v0.3）
> 决策（2026-10-18）：本期不做。当前 `AgentRuntime.call/stream` 单轮入口已端到端验证通过，满足近期需求。原语层为多步编排 / 跨 provider 切换 / langchain4j 接入等进阶场景预留，待有明确需求再启动。
- [-] I1 `TurnScope` / `openTurn(uid, sessionId)` / `instrumentTurn(uid, sessionId, Flux)` — TURN 作用域原语（同步 + 流式对称）
- [-] I2 `TurnBuilder` / `turn(uid, sessionId)` — 最省事流式入口（一行完成 openTurn + 记忆 advisor + 调用 + FINAL_REPLY + close）
- [-] I3 `chatFor(sessionId)` — 返回已挂记忆 advisor、绑好 sessionId 的 `ChatClient`（省 advisor 样板）
- [-] I4 ChatClient 注册表 + `chatClient(name)` — 多 provider 按名取，每个经 `ObservingChatModel` 装饰
- [-] I5 `call/stream` 内部重构为原语的默认组合（行为不变，代码复用）
- [x] I6 `ObservingChatModel.stream()` 补流式埋点（MODEL_CALL span 在流式下的开/关 + token 累积）
- [-] I7 `ensureStarted` 公开 + 并发语义加固（`computeIfAbsent` 或锁，应对用户自调 `openTurn` 的竞态）
- [-] I8 langchain4j 接入路 1（框架无关服务 + 手动埋点）/ 路 2（model 适配器）
- [-] I9 `WorkflowTemplate` 脚手架（多 TURN + 记忆 + 事件，样板重复过多再抽）

### J. ID 模型修正（本轮已完成）
- [x] J1 `AgentRuntime.call/stream` 签名补 `uid`（修复 `agent_session.user_id` 列存在但从不写入的 bug）
- [x] J2 两层 ID 模型落地：`uid → sessionId`，记忆按 session 共享累积，seq 按 session 作用域（`UNIQUE(session_id, seq)`）
- [x] J3 流式 context-propagation（`SpanContextHolder` 两参 + `Hooks.enableAutomaticContextPropagation()`），reactor 线程正确恢复 span 上下文
- [x] J4 demo `/chat/stream` SSE 端点 + 端到端验证（流式 token / TOOL_CALL span 在 reactor 线程落库 / 并行工具起止重叠）

## 待定决策

- [ ] D-1 core 共享基类最终放 core（已倾向），还是单独 base 模块？
- [ ] D-2 先实跑 demo 验证，还是继续堆功能？
- [ ] D-3 MySQL 适配优先级（V1 是否就要双 DB）？
- [ ] D-4 异步写入：Redis 队列够用，还是尽早接 MQ？

---

> 维护约定：完成某项任务后，把对应 `[ ]` 改为 `[x]`；开始做改为 `[~]`；并更新顶部"最后更新"日期。
