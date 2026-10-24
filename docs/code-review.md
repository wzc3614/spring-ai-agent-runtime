# 代码评审问题清单

> 评审日期：2026-10-24
> 最后更新：2026-10-24（memory 模块 P0 / P1 已修，顺带修了 M6）
> 范围：api、core、memory、observability、capability、spill、starter。demo 只作演示，未计入。
> 状态：`[x]` 已修复 / `[~]` 部分修复 / `[ ]` 未修复

共 28 条：已修复 6，部分修复 3，未修复 19。P0 已清空。未修复里仍有 8 条 P1，集中在并行工具超时、会话状态和调度线程。

| 级别 | 已修复 | 部分修复 | 未修复 |
| --- | ---: | ---: | ---: |
| P0 | 1 | 0 | 0 |
| P1 | 4 | 1 | 8 |
| P2 | 1 | 1 | 9 |
| P3 | 0 | 1 | 2 |

写队列修复依赖 Redis 6.2+ 的 `LMOVE`。

## 已修复

### [x] M1 P0 memory：异步写先弹出再落库，崩溃或坏消息会丢对话

原位置：`MemoryWriteQueue.drain` / `MemoryWriteWorker.writeOrRetry`。

Worker 拿到锁后用 `LPOP` 排空队列，再开事务写库。进程死在弹出和提交之间，或队列里有一条坏 JSON 时，已经弹出的消息不在 Redis，也不在数据库。

修复：入队列表只负责接收。认领时用 `LMOVE` 原子搬到处理中列表，数据库提交成功才删除。坏 JSON 和重试耗尽的任务在同一个脚本里转入死信。进程死在落库前，消息留在处理中列表，下次继续写。

### [x] M2 P1 memory：入队和待写集合不是原子操作，消息会滞留

原位置：`MemoryWriteQueue.enqueue` / `removeFromPending`。

`RPUSH` 和 `SADD` 分成两次。只完成入队时，Worker 扫描不到这个 session。Worker 看到长度为 0 之后、`SREM` 之前，生产者又入队，`SREM` 会把刚加回去的 session 删掉。

修复：入队和登记待写 session 放在同一个 Lua 里。待写集合只在入队列表和处理中列表都空时才移除，判断也在 Lua 里完成。

### [x] M3 P1 memory：序号按数据库最大值重建，冲突被静默丢掉

原位置：`RedisSessionSeqProvider.nextSeq` / `JdbcMessageRepository.append`。

序号键丢失时只用数据库最大 seq 做 `SETNX` 再 `INCR`，队列里还没落库的更大序号不算进去。新消息撞上 `UNIQUE(session_id, seq)` 后，`ON CONFLICT DO NOTHING` 被当成写成功。读路径还会把同一 seq 的库内行和队列行一起交给模型。

修复：水位取数据库最大 seq 和队列未落库 seq 的较大值，在 Lua 里抬高后再 `INCR`，已有键只升不降。`append` 返回插入行数：同一 `messageId` 视为幂等重试，不同则重新分配 seq。读历史经 `SessionHistory` 按 seq 去重，数据库行优先。

### [x] M4 P1 memory：压缩锁会过期，版本号用读出来的 latest+1

原位置：`SummaryCompactor.compact` / `JdbcSummarySnapshotRepository.save`。

锁默认 120 秒，分批摘要加超时重试可以超过 TTL，第二个任务会进来。两边都用当时读到的 `version + 1` 插入，撞上 `UNIQUE(session_id, version)`。失败快照再用同一个版本号，唯一约束再次失败。

修复：压缩期间按 TTL 的三分之一续期，续期失败就不再写快照。版本号改为数据库里的 `MAX(version) + 1`，唯一约束冲突重试。

### [x] M5 P1 memory：每轮把整个会话历史装进内存

原位置：`CompactingChatMemory.get` / `SummaryCompactor.loadAndMerge`。

已有 `findBySession(sessionId, fromSeq)`，读路径和压缩路径都没用，每次把全表拉回内存再过滤。

修复：先取可用摘要的边界，只加载 `seq` 更大的原文，队列任务同样按边界过滤。压缩的预算校验和读路径用同一段尾部历史。

### [x] M6 P2 memory：clear 与 Worker 同时 drain

原位置：`CompactingChatMemory.clear`。

`clear` 不拿 session 写锁就 `LPOP`，并删除数据库行。和 Worker 交错时，消息会丢，或者 Worker 把刚删的数据写回去。

修复：`discardSession` 持有和 Worker 同一把写锁，锁内先清 Redis 队列再删数据库行。锁被占用时短暂重试，仍拿不到就抛异常。

## 部分修复

### [~] Q1 P1 全局：并发和持久化路径没有测试挡住回归

测试原先集中在 TurnHook 和 Skill 管道。写队列崩溃、序号重建、并行超时、信号量中断、会话并发首包、压缩版本冲突都没有断言。

已补充 `SessionHistoryTest`（同一 seq 以数据库为准、边界过滤）和 `RedisSessionSeqProviderTest`（水位取队列与数据库的较大值）。写队列崩溃恢复、并行超时、会话首包并发、压缩版本冲突仍然没有测试。

### [~] G3 P2 全局：同一段逻辑写了多份

`WriteTask` 转 `AgentMessage` 已收成 `WriteTaskConverter`。记忆模块的 Redis 解锁和续期已收成 `RedisCompareLock`。

仍各写一份的：`CompactingChatMemory` 和 `SummaryCompactor` 的摘要 JSON 解析，以及 observability 里私有的时间戳转换（memory 侧已有 `JdbcUtils`）。

### [~] G4 P3 全局：调度周期、重试次数、摘要 schema 版本仍是字面量

写队列的锁 TTL、clear 重试次数已经进 `WriteQueueConstants`。压缩锁续期间隔进了 `CompactionConstants.LOCK_RENEW_DIVISOR`。

仍写在实现里的：Worker 和观测消费的 `fixedDelay = 1000`、摘要 `schemaVersion = 2`、观测消费的批大小和重试次数。`CompactConfig` 里的锚点关键词仍是中文默认值。

## 未修复

### P1

| 编号 | 模块 | 问题 | 位置 |
| --- | --- | --- | --- |
| C1 | core | 工具超时从串行等待时才开始计时。任务并行提交，但 `future.get(timeout)` 在循环里一个接一个调用，后一个任务的超时起点是前一个返回之后。前面的工具若跑满 30 秒，后面的工具实际墙钟会加上这段等待 | `ParallelToolExecutor.invoke` 带超时重载 |
| C2 | core | `semaphore.acquire()` 写在 try 外面，失败时 `finally` 仍 `release()`，许可数会超过上限。每次调用还新开一个虚拟线程池，`shutdown()` 不打断也不等待 | `ParallelToolExecutor` 两个 `invoke` |
| C3 | core | 观测装饰器在成功和失败路径都调用 `Thread.interrupted()`，外层 `cancel(true)` 留不住。幂等工具还会继续重试，模型已经收到超时 | `ObservabilityToolCallback.observe` |
| C4 | core | `DanglingToolCallDetector.repair` 没有接到读历史的路径。崩溃留在「模型已经发出 tool call、结果还没写上」时，下一轮会把不完整协议送给模型。修复策略还会整段丢掉 assistant，留下孤立的 tool 响应 | `DanglingToolCallDetector` |
| R1 | starter | `ensureStarted` 先查本机 Map 再插入，并发首包会打到主键上。会话 Map 和 SESSION span 只在本机，另一个实例上的 `close` 结束不了这边的 span。`touch()` 和 session 乐观锁没有调用方 | `AgentRuntime.ensureStarted` / `close` |
| R2 | starter | 流式调用在订阅线程设置 Span 和预算，`doFinally` 在另一条线程上 `clear`。发起线程留下上一个会话的 spanId，结束线程可能清掉别的请求刚恢复的上下文 | `AgentRuntime.finishStream` / `closeSkill` |
| O1 | observability | 自动配置只有 `@EnableScheduling`，没有自己的调度池。默认单线程上，观测消费的 `XREADGROUP` 会阻塞约 2 秒，记忆落库排在它后面 | `AgentRuntimeAutoConfiguration` / `ObservabilityStreamConsumer.run` |
| K1 | capability | Skill 准备抛出任何运行时异常都退回全部工具。正常路径里被 Skill 管住的工具只有命中才放行，失败路径把这道门打开 | `DefaultSkillRuntime.prepare` |

### P2

| 编号 | 模块 | 问题 | 位置 |
| --- | --- | --- | --- |
| M7 | memory | 每次摘要调用新建一个虚拟线程池。超时后 `shutdownNow` 不等待，HTTP 客户端不响应中断时，重试和前一次调用会重叠 | `CompactionFallbackHandler.timed` |
| C5 | core | 失败结果在 `error == null` 时走到 `error.getClass()`，回填模型前抛空指针。超时 JSON 里的工具名没有转义 | `ParallelToolCallingManager.toToolResponseContent` |
| C6 | core | `catch (Throwable)` 会把 `Error` 包成 `ToolExecutionException`。重试前不看中断标志，外层已取消时幂等工具仍会再调一次 | `GovernedToolCallback.govern` |
| C7 | core | `SessionLifecycleCleaner` 没有注册成监听器。`MicroCompactor` 没有进读路径。`agent_tool_call` 表没有写入路径 | `SessionLifecycleCleaner` / `MicroCompactor` |
| O2 | observability | span 的事件缓冲是普通 `ArrayList`。`endSpan` 并发 `remove` 时，事件会写进已经被摘走的列表，或在 span 结束后再也没人 flush | `SpanTreeObservabilityRecorder.recordEvent` |
| O3 | observability | 事件因父 span 还没入库而失败时，重试三次后进死信。间隔取决于调度，span 稍慢就会丢事件 | `ObservabilityStreamConsumer.retryOrDlq` |
| K2 | capability | 钩子打断只完成 `interruptSignal`，Skill 准备任务没有取消，路由模型调用会在后台跑完 | `TurnHookGate.awaitPrepare` |
| G1 | 全局 | spill 模块只有 `package-info`，表已经在 V1 建好。观测表和 skill 表的迁移在 memory 模块，实现却在 observability 和 capability。编排入口放在 starter，和自动配置绑在一起 | `agent-runtime-spill` / Flyway / starter |
| G2 | 全局 | `ON CONFLICT`、`::jsonb`、`agent_runtime_obs` schema 写在具体 JDBC 类里。换 MySQL 要改每一条 SQL | `JdbcMessageRepository` / `JdbcSpanRepository` / `JdbcSummarySnapshotRepository` |

### P3

| 编号 | 模块 | 问题 | 位置 |
| --- | --- | --- | --- |
| G5 | 全局 | `AgentRuntime`、`CompactingChatMemory`、`SummaryCompactor`、`DefaultSkillRuntime` 构造参数过长。应拆会话登记和轮次执行，而不是改成 setter 注入 | 上述四个类 |
| G6 | 全局 | `TurnHookContext` 用 commons-lang3 的 `StringUtils`，其余路径用 Spring 的 `StringUtils` 和 `CollectionUtils`。空白字符串上的行为不一致 | `TurnHookContext` |
