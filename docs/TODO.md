# Agent Runtime 待办与状态

> 最后更新：2026-10-17（端到端全通：工具调用+观测 span 树�完整；修 4 个 bug）
> 最后更新：2026-10-17（Spring AI 1.0.0 GA；A7 接入 ParallelToolCallingManager 并 demo 验证并行生效）
> 状态图例：`[ ]` 待办 / `[~]` 进行中 / `[x]` 完成 / `[-]` 暂缓

## 已完成（基线）

- [x] 项目骨架：parent pom + 8 模块 + demo + Flyway V1 schema + LICENSE(Apache 2.0)
- [x] `api` 模块：domain(9) / enums(9) / repository(8) / spi(5) / lifecycle(3) / memory 配置(3) / spill 配置(1) / exception(2)
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
- [ ] B2 九段式 LLM 摘要 + 增量合并更新（M3）
- [ ] B3 异步写入路径实测验证（worker 排空 / Redis seq 初始化 / A1 读侧合并）
- [ ] B4 `MessageWritePolicy` 用户扩展示例/文档

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
- [ ] D4 OpenTelemetry/OTLP 导出
- [ ] D5 自建轻量可视化 Web UI
- [ ] D6 查询 + ASCII 树渲染器 + REST（本期不做）

### E. Capability（M2，未建）
- [ ] E1 Skill 加载（classpath + DB 动态，DB 覆盖同名）
- [ ] E2 MCP/工具热插拔（Nacos 默认 + 降级）
- [ ] E3 差量刷新 + 引用计数延迟关闭
- [ ] E4 `DynamicToolCallbackProvider`
- [ ] E5 内置原子工具

### F. HIL 人工审批（M4，未建）
- [ ] F1 HIL Advisor
- [ ] F2 风险等级 Policy Hook
- [ ] F3 Approval REST API
- [ ] F4 `JdbcApprovalRepository`
- [ ] F5 `ApprovalNotifier` SPI
- [ ] F6 复用悬空修复做审批恢复

### G. 工具失败/重试（已设计未建）
- [ ] G1 `GovernedToolCallback`/`RetryToolCallback`
- [ ] G2 `DefaultToolFailureClassifier`（依赖 A5）

### H. 横切 / 基建 / 质量
- [ ] H1 单元测试 + 集成测试
- [x] H2 实跑 demo 端到端验证（启动/Flyway/记忆/观测 span+event/工具调用 全通；修 4 个 bug：MessageRoleEnum 大小写、event payload jsonb→text、event FK 缓冲、ChatOptions→OpenAiChatOptions）
- [ ] H3 README / 使用文档 / 开源说明
- [ ] H4 `@ConfigurationProperties` 调优配置
- [ ] H5 MySQL 适配验证
- [-] H6 Kafka/RocketMQ 异步（暂缓，Redis 队列先行）
- [-] H7 长期记忆 pgvector/Milvus（M6 远期）

## 待定决策

- [ ] D-1 core 共享基类最终放 core（已倾向），还是单独 base 模块？
- [ ] D-2 先实跑 demo 验证，还是继续堆功能？
- [ ] D-3 MySQL 适配优先级（V1 是否就要双 DB）？
- [ ] D-4 异步写入：Redis 队列够用，还是尽早接 MQ？

---

> 维护约定：完成某项任务后，把对应 `[ ]` 改为 `[x]`；开始做改为 `[~]`；并更新顶部"最后更新"日期。
