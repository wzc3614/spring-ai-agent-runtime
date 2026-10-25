# Agent Runtime 待办与状态

> 最后更新：2026-10-25（Starter 提供默认关闭的单轮调用树页面 `/agent-runtime/trace`）
> 最后更新：2026-10-24（工具结果压缩并入 Memory：PostgreSQL JSONB，查询/操作/搜索三类策略）
> 最后更新：2026-10-23（移除 HIL 人工审批模块及其契约、表和待办）
> 最后更新：2026-10-23（基础版 LLM 摘要记忆落地：异步压缩 + Compaction 角色 + ChatHistorySummarizer SPI；放弃九段式）
> 最后更新：2026-10-17（端到端全通：工具调用+观测 span 树完整；修 4 个 bug）
> 最后更新：2026-10-17（Spring AI 1.0.0 GA；A7 接入 ParallelToolCallingManager 并 demo 验证并行生效）
> 状态图例：`[ ]` 待办 / `[~]` 进行中 / `[x]` 完成

## 已完成（基线）

- [x] 项目骨架：parent pom + 7 模块 + demo + Flyway V1 schema + LICENSE(Apache 2.0)
- [x] `api` 模块：domain / enums / repository / spi / lifecycle / memory 配置 / exception
- [x] `memory` 模块：4 个 JDBC repo、TransactionPortImpl、LocalTokenEstimator、BudgetCalculator、工具结果压缩、异步写入路径（WriteTask/Queue/Worker/SeqProvider/Policy）
- [x] `starter`：AgentRuntime + Builder + Factory、CompactingChatMemory + Builder + Factory、MessageConverter、AgentRuntimeAutoConfiguration
- [x] `demo`：BusinessTool / DemoAgentService / DemoController / Application / application.yml
- [x] 最小可用端到端编译 + 打包通过
- [x] Spring AI 1.0.0-M6 → 1.0.0 GA 升级（7 处代码适配 + 3 个 pom 调整，已端到端验证：工具调用 / span 树 / 记忆落库全通）
- [x] I6 `ObservingChatModel.stream()` 流式埋点（MODEL_CALL span 在流式下的开/关 + token 累积）

## 待建任务

### A. Core 共享内核（当前空模块）
- [x] A1 `ParallelToolExecutor` — 虚拟线程并发执行工具 + 结果顺序对齐
- [x] A2 `DanglingToolCallDetector` — 加载历史时检测/修复悬空 tool_call
- [x] A3 `SessionLifecycleCleaner` — 会话结束协调各子系统清理
- [x] A4 `BaseToolCallbackDecorator` + `ToolObjectResolver` — 装饰器基类 + `@Tool`→`ToolCallback`
- [x] A5 工具元数据注册表（risk level + idempotent）+ `DefaultToolFailureClassifier`
- [x] A7 接入 `ParallelToolCallingManager` — 自实现 `ToolCallingManager`，用 `ParallelToolExecutor` 并发执行一轮内多个 tool call，经 `ParallelToolCallingAutoConfiguration`（`@AutoConfigureBefore` Spring AI 的 `ToolCallingAutoConfiguration`）替换默认 `DefaultToolCallingManager`；观测装饰器链保持不变。附：`SpanContextHolder` 改 `InheritableThreadLocal` 以让虚拟线程继承 TURN spanId/sessionId（已 demo 验证：两个 TOOL_CALL span 起止时间完全重叠，并行生效）

### B. Memory 补齐
- [x] B1 工具结果读路径渲染：`ToolResultRenderer` 在 `CompactingChatMemory.get()` 和会话摘要前按策略替换视图（查询过期 / 搜索摘要 / 操作保留）。`MicroCompactor` 的 evidence 占位符已移除
- [x] B2 基础版 LLM 摘要（放弃九段式）：历史超预算时异步调 `ChatModel` 压缩为一段摘要，存 `agent_summary_snapshot`（`sections` 通用 JSON + `summarized_up_to_seq` 边界，V4 迁移）；读侧注入 `CompactionMessage`（新 `MessageRoleEnum.COMPACTION`，借 `MessageType.SYSTEM`）；`ChatHistorySummarizer` SPI 供用户自定义；`MemoryBudgetContextHolder` 由 `AgentRuntime` 写入 fixedOverhead；per-session Redis 锁 + double-check，失败不重试
- [ ] B3 异步写入路径实测验证（worker 排空 / Redis seq 初始化 / A1 读侧合并）
- [ ] B4 `MessageWritePolicy` 用户扩展示例/文档
- [ ] B5 摘要路径实测验证（长对话触发压缩 / 下一轮注入 summary / 并发不重复压缩 / 失败不重试）

### C. 工具结果原文（原 Spill，并入 Memory）
- [x] C1 取消独立 spill 模块和 MinIO。原文写入 `agent_message.payload`（JSONB），`agent_spill_record` 由 V7 删除
- [x] C2 内置三种策略：`QUERY` 过期后删原文并留短句、`RETAIN` 完整保留并在摘要边界后回捞、`SUMMARIZE` 确定性摘要。未注册工具默认 `QUERY`
- [x] C3 自定义 `ToolResultCompactor`，按 code 挂到工具规则
- [x] C4 物理清理走 `ToolPayloadCleaner`，与写入 worker 共用 session 写锁。当轮原文不压缩
- [x] C5 单条原文超过 1MB 截断后仍视为工具调用成功

### D. Observability（M1 埋点 + M5 平台化，未建）
- [x] D1 Span/Event 树构建器（`SpanTreeObservabilityRecorder` + `ObservabilityRecorder`/`ObservabilitySink` SPI）
- [x] D2 `JdbcSpanRepository` / `JdbcEventRepository`（+ RowMappers）
- [x] D3 基础埋点接进调用链（SESSION/TURN + FINAL_REPLY；LlmCallSpan via ObservingChatModel 记 usage/finishReason；ToolCallSpan via ObservabilityToolCallback 记 TOOL_INPUT/OUTPUT；span 加 attributes）
- [x] D3.1 日志异步写入：Redis Stream + 消费组（`RedisStreamObservabilitySink`/`ObservabilityStreamQueue`/`ObservabilityStreamConsumer`），`mode=async` 默认；失败重投 + DLQ + XCLAIM 接管；FK 顺序靠 `findById` 校验 + 重投 + `ON CONFLICT DO NOTHING` 幂等
- [ ] D4 OpenTelemetry/OTLP 导出
- [ ] D5 完整可视化 Web UI（当前只有文本树）
- [x] D6 单轮文本树：`TraceTreeRenderer` 画选中的 TURN；Starter 的 `GET /agent-runtime/trace` 默认关闭，`agent.runtime.trace.enabled=true` 才注册。不展示费用，工具入出参超过 200 字截断。`thinking` / `response` 保留换行，超过 800 字截断

### E. Capability（M2，未建）
- [x] E1 Skill 加载（classpath + DB 动态，DB 覆盖同名）+ 关键词/轻量模型路由 + 渐进加载 + Skill 事件
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

### J. ID 模型修正（本轮已完成）
- [x] J1 `AgentRuntime.call/stream` 签名补 `uid`（修复 `agent_session.user_id` 列存在但从不写入的 bug）
- [x] J2 两层 ID 模型落地：`uid → sessionId`，记忆按 session 共享累积，seq 按 session 作用域（`UNIQUE(session_id, seq)`）
- [x] J3 流式 context-propagation（`SpanContextHolder` 两参 + `Hooks.enableAutomaticContextPropagation()`），reactor 线程正确恢复 span 上下文
- [x] J4 demo `/chat/stream` SSE 端点 + 端到端验证（流式 token / TOOL_CALL span 在 reactor 线程落库 / 并行工具起止重叠）

---

> 维护约定：完成某项任务后，把对应 `[ ]` 改为 `[x]`；开始做改为 `[~]`；并更新顶部"最后更新"日期。
