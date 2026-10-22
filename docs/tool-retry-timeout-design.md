# 工具调用失败重试与单调用超时 — 技术方案

> 版本：v0.1（设计稿，待 zhaochen review 后再落地）
> 最后更新：2026-10-23
> 关联：`docs/design.md` §4.8、`docs/TODO.md` G 组（工具失败/重试，已设计未建）

## 1. 背景与问题

当前工具调用链路（`ParallelToolCallingManager` + `ParallelToolExecutor`）已能并行执行一轮内多个 tool call，但存在两块缺口：

1. **没有单调用超时**：`ParallelToolExecutor.invoke` 用 `future.get()` 无限等待，一个慢工具（下游 hang 死）会拖住整批结果返回；`ParallelToolCallingManager.executeSingleTool` 直接 `toolCallback.call(...)`，也没有对单次调用设上限。
2. **没有失败重试**：`executeSingleTool` 仅在抛 `ToolExecutionException` 时交给 `toolExecutionExceptionProcessor` 转成字符串回填模型，**不重试**；其它异常直接抛出，经 `ParallelToolExecutor.await` 重新抛出后会**整批崩溃**（一个工具失败连累同轮所有兄弟工具）。

而项目里其实已经为重试铺好了"半成品"：

- `ToolMeta`（riskLevel + idempotent）、`ToolMetaRegistry`、`DefaultToolMetaRegistry` — 元数据已就绪。
- `ToolFailureClassifier` SPI + `DefaultToolFailureClassifier` — 失败分类（TRANSIENT / TERMINAL / AMBIGUOUS）已实现。
- `FailureCategoryEnum`、`ToolCallStatusEnum.AMBIGUOUS`、`ToolCall.retryCount` / `failureCategory` 字段 — 落库模型已预留。

结论：**分类、元数据、落库字段都齐了，缺的是把"超时 + 重试"接进调用链，并让 `ParallelToolExecutor` 支持按任务单独设超时。**

## 2. 设计目标

1. `ParallelToolExecutor` 支持每个任务独立超时，超时即中断该任务的虚拟线程，**不影响兄弟任务**。
2. 工具调用失败按 `ToolFailureClassifier` 分类：TRANSIENT + 幂等 → 自动重试；TERMINAL / AMBIGUOUS / 非幂等 → 不重试。
3. 单工具失败（含超时、重试耗尽）**不再连累同轮其它工具**，而是转成"错误结果"回填模型，让对话继续。
4. 复用现有元数据 / 分类器 / 落库字段，**不重造**，遵守"叠加而非替代"哲学，以装饰器方式接入。
5. 超时、重试参数可按工具配置，并提供全局默认值（`@ConfigurationProperties`）。

## 3. 现状回顾（关键代码位置）

- `agent-runtime-core/.../concurrency/parallel/ParallelToolExecutor.java` — 虚拟线程 + Semaphore，`future.get()` 无超时，`await` 抛异常会整批崩。
- `agent-runtime-core/.../concurrency/parallel/ParallelToolCallingManager.java` — `executeSingleTool` 直接 `call`，仅 catch `ToolExecutionException`。
- `agent-runtime-core/.../tool/ToolMeta.java` — 只有 `toolName / riskLevel / idempotent`，**无超时、无重试参数**。
- `agent-runtime-core/.../tool/DefaultToolFailureClassifier.java` — 已分类，但**没人调用它**。
- `agent-runtime-.../observability/ObservabilityToolCallback.java` — 装饰器范例（`BaseToolCallbackDecorator`）。
- `agent-runtime-.../runtime/AgentRuntimeBuilder.java` — 工具包装点：`raw -> ObservabilityToolCallback`。

## 4. 总体方案

分两层，各司其职，互不重叠：

```
ParallelToolCallingManager.executeToolCalls
  -> 查 ToolMeta 取 timeoutMs，组装带超时的 task
  -> ParallelToolExecutor.invoke(tasks, maxConcurrency, timeouts)   [层1: 单调用超时]
       每个 task = GovernedToolCallback.call(...)
         -> GovernedToolCallback (重试 + 分类)                        [层2: 失败重试]
              -> ObservabilityToolCallback (span/事件)                 [已有]
                   -> raw ToolCallback.call(...)
```

- **层 1（超时）放 `ParallelToolExecutor`**：用户明确要求"给每个工具调用设单独超时"，且超时是并发原语的能力，放执行器最自然。语义为**单次工具调用的总墙钟上限（含其全部重试）**。
- **层 2（重试 + 分类）放 `GovernedToolCallback` 装饰器**：与 `ObservabilityToolCallback` 同构，符合"叠加而非替代"。重试需要 `ToolFailureClassifier`，分类逻辑天然属于治理装饰器，不属于并发原语。

两层只有一个超时来源（执行器），只有一个重试来源（装饰器），职责清晰、无重叠。

## 5. 详细设计

### 5.1 扩展 ToolMeta：增加超时与重试参数

`ToolMeta` 现有 `toolName / riskLevel / idempotent`，新增三个字段（无参构造 + setter，遵守规范第 5 条）：

| 字段 | 类型 | 含义 | 默认（保守） |
|---|---|---|---|
| `timeoutMs` | `Long`（null 表示用全局默认） | 单次工具调用总墙钟上限（含重试） | 全局默认 30s |
| `maxAttempts` | `Integer`（null 表示用全局默认） | 最大尝试次数（含首次，>=1） | 全局默认 1（不重试） |
| `backoff` | `BackoffPolicy` | 重试退避策略 | 全局默认固定 200ms |

> 默认 `maxAttempts=1` 即"默认不重试"，保持与现状行为一致；只有显式声明幂等 + 配 attempts>1 的工具才会重试，安全优先。

新增 `BackoffPolicy`（小值对象，放 `core.tool`）：

```java
// 退避策略：FIXED 固定间隔 / LINEAR 线性增长 / EXP 指数退避（带上限）
public class BackoffPolicy {
    private Type type;          // FIXED / LINEAR / EXP
    private long initialMs;     // 首次退避
    private double multiplier;  // EXP/LINEAR 增长系数
    private long maxIntervalMs; // 退避上限
    // 无参构造 + setter + 静态工厂 fixed(ms) / exp(initial, mult, max)
}
```

### 5.2 ParallelToolExecutor：支持按任务超时 + 失败隔离

#### 5.2.1 新增结果包装

当前 `invoke` 返回 `List<T>`，失败靠抛异常（会整批崩）。改为返回 `List<TaskResult<T>>`，把成功/失败/超时都装进结果，**单任务失败不再连累兄弟**：

```java
public final class TaskResult<T> {
    private final boolean success;
    private final T value;         // 成功时的值
    private final Throwable error; // 失败时的异常
    private final boolean timedOut;// 是否超时

    public static <T> TaskResult<T> ok(T value) { ... }
    public static <T> TaskResult<T> fail(Throwable e) { ... }
    public static <T> TaskResult<T> timeout() { ... }
    // getter ...
}
```

#### 5.2.2 新增带超时的重载

保留原 `invoke(List<Supplier<T>>, int)` 兼容旧调用；新增：

```java
// 每个任务带独立超时；timeouts 与 tasks 等长、顺序对齐；元素 null 表示不限时
public <T> List<TaskResult<T>> invoke(List<Supplier<T>> tasks,
                                      int maxConcurrency,
                                      List<Duration> timeouts)
```

执行逻辑（在现有虚拟线程 + Semaphore 基础上）：

- 提交任务到 `newVirtualThreadPerTaskExecutor()`，Semaphore 限并发（不变）。
- 收集时改为 `future.get(timeout)`：
  - 正常完成 -> `TaskResult.ok(value)`。
  - `TimeoutException` -> `future.cancel(true)`（中断虚拟线程，让阻塞的下游调用抛 `InterruptedException` 尽快退出）-> `TaskResult.timeout()`。
  - `ExecutionException` -> 取 cause -> `TaskResult.fail(cause)`。
  - `InterruptedException` -> 中断当前线程 + 把已完成的兄弟结果尽量返回（不抛崩整批）。
- 顺序仍按入参对齐返回。

> 关键点：`future.cancel(true)` 会中断虚拟线程，被中断的阻塞 IO（纯 hang）会抛 `InterruptedException`，从而真正释放线程，避免"超时了但线程还在跑"的资源泄漏。

#### 5.2.3 不做的事

- 不在执行器里做重试/分类：那是装饰器职责。
- 不引入 `CompletableFuture.orTimeout` 等替代 API，保持虚拟线程 + `Future` 模型与现状一致，diff 最小。

### 5.3 GovernedToolCallback：重试 + 分类装饰器

新增 `agent-runtime-core/.../tool/GovernedToolCallback extends BaseToolCallbackDecorator`。

#### 5.3.1 依赖

- `ToolMetaRegistry`（取 `maxAttempts / backoff / idempotent`）。
- `ToolFailureClassifier`（分类异常）。
- 全局默认配置（`ToolGovernanceProperties`，见 5.5），用于 `ToolMeta` 字段为 null 时兜底。

#### 5.3.2 call 流程

```
meta = metaRegistry.getMeta(toolName)
attempts = resolveAttempts(meta)        // meta.maxAttempts ?: globalDefault
backoff = resolveBackoff(meta)
for (i = 1; i <= attempts; i++) {
    try {
        return delegate.call(input, ctx)   // 委托给内层（ObservabilityToolCallback / raw）
    } catch (Throwable e) {
        category = classifier.classify(e, buildToolCallView(meta))
        if (category == TRANSIENT && meta.isIdempotent() && i < attempts) {
            sleep(backoff.next(i))          // 退避，响应中断抛 InterruptedException 即放弃
            continue
        }
        // TERMINAL / AMBIGUOUS / 非幂等 / 重试耗尽 -> 不再重试
        rethrowAsToolExecutionException(e, category)   // 转成 Spring AI ToolExecutionException 抛出
    }
}
```

要点：

- **AMBIGUOUS 绝不自动重试**（非幂等 + 超时类，结果可能已生效），与 `DefaultToolFailureClassifier` 语义一致。
- **TRANSIENT 但非幂等也不重试**（保守，避免重复扣款等副作用）。
- 退避 `sleep` 响应中断：被中断则停止重试、抛出，避免超时后还在退避等待。
- 重试耗尽 / 不可重试 -> 把异常包装成 `ToolExecutionException` 抛出，交给 `ParallelToolCallingManager` 已有的 `toolExecutionExceptionProcessor` 转成字符串回填模型（**复用现有处理器，行为与现状对齐**）。

#### 5.3.3 与观测装饰器的顺序

`AgentRuntimeBuilder.build()` 的包装顺序改为：

```
raw -> GovernedToolCallback -> ObservabilityToolCallback（最外层）
```

即观测在最外层：一次逻辑工具调用 = 一个 `TOOL_CALL` span，多次重试尝试作为该 span 内的事件（可后续加 `TOOL_RETRY` 事件），span 的起止覆盖整个重试过程。这样观测口径与"一次工具调用"对齐，不被重试拆碎。

> 装饰器顺序是构建期决定的，运行期 `ParallelToolCallingManager` 拿到的就是已包装好的 `ToolCallback`，无需感知治理/观测层。

### 5.4 ParallelToolCallingManager：接入超时与结果映射

改动最小化：

1. 构造器新增可选依赖 `ToolMetaRegistry`（缺省时用一个空注册表，全部走默认元数据 -> 全局默认超时）。
2. `executeToolCalls` 组装 tasks 时，按 `toolName` 查 `ToolMeta` 取 `timeoutMs`（null 走全局默认），构造 `List<Duration> timeouts`。
3. 调用新 `parallelToolExecutor.invoke(tasks, maxConcurrency, timeouts)`，拿到 `List<TaskResult<String>>`。
4. 结果映射（替换当前 `results.get(i)` 直接拼装）：

   | TaskResult | ToolResponse 内容 |
   |---|---|
   | `ok(value)` | `value`（同现状） |
   | `timeout()` | 结构化错误串，如 `{"error":"tool_timeout","tool":...,"timeoutMs":...}` |
   | `fail(e)` 且 `executeSingleTool` 内部已转 `ToolExecutionException` 并被 processor 处理 | processor 输出（同现状） |

   实现上：`executeSingleTool` 仍负责"抛 `ToolExecutionException` -> processor 转字符串"的现有逻辑；`GovernedToolCallback` 抛出的 `ToolExecutionException` 在 `executeSingleTool` 被处理器转成字符串后**正常返回**（`TaskResult.ok(errorString)`），而真正的超时/未捕获异常才落到 `TaskResult.timeout()/fail()`。

5. 单工具超时/失败 -> 转成错误 ToolResponse，**不抛出**，同轮其它工具结果照常返回，模型拿到一份"部分成功 + 错误说明"的响应自行决策。

### 5.5 全局默认配置

新增 `@ConfigurationProperties("agent.runtime.tool-governance")`（放 starter），字段：

| 属性 | 默认 | 含义 |
|---|---|---|
| `default-timeout-ms` | 30000 | 默认单调用总超时 |
| `default-max-attempts` | 1 | 默认最大尝试次数（不重试） |
| `default-backoff.type` | FIXED | 默认退避类型 |
| `default-backoff.initial-ms` | 200 | 默认首次退避 |
| `default-backoff.multiplier` | 2.0 | EXP/LINEAR 系数 |
| `default-backoff.max-interval-ms` | 2000 | 退避上限 |

由 `ParallelToolCallingAutoConfiguration` 注入到 `ParallelToolCallingManager` 与 `GovernedToolCallback`。`ToolMeta` 字段为 null 时用它兜底。

### 5.6 落库（可选，本期最小做）

`ToolCall` 已有 `retryCount / failureCategory`。若接入 `ToolCallRepository` 写入：在 `GovernedToolCallback` 重试循环里累加 `retryCount`，终态时写 `failureCategory`。本期可先**只在内存/日志记录**，落库待 HIL/重试状态机一起做（避免与 memory 子系统的异步写入路径耦合）。文档中标注为"后续接入点"。

## 6. 改动清单（review 用，未动手）

| 模块 | 文件 | 改动 |
|---|---|---|
| core | `tool/ToolMeta.java` | 新增 `timeoutMs / maxAttempts / backoff` 字段 + getter/setter |
| core | `tool/BackoffPolicy.java` | **新增** 退避策略小值对象 |
| core | `tool/GovernedToolCallback.java` | **新增** 重试+分类装饰器 |
| core | `concurrency/parallel/TaskResult.java` | **新增** 任务结果包装 |
| core | `concurrency/parallel/ParallelToolExecutor.java` | 新增带 `List<Duration>` 超时的重载；`await` 改为产出 `TaskResult`，超时 `cancel(true)` |
| core | `concurrency/parallel/ParallelToolCallingManager.java` | 注入 `ToolMetaRegistry`；按工具取超时；结果映射 |
| starter | `autoconfigure/ToolGovernanceProperties.java` | **新增** `@ConfigurationProperties` |
| starter | `autoconfigure/ParallelToolCallingAutoConfiguration.java` | 注入 properties + `ToolMetaRegistry` + `ToolFailureClassifier` |
| starter | `runtime/AgentRuntimeBuilder.java` | 包装顺序：`raw -> Governed -> Observability` |
| demo | `BusinessTool.java` | （可选）加一个慢/会失败的工具演示超时与重试 |

## 7. 行为矩阵

| 工具元数据 | 异常类型 | 分类 | 行为 |
|---|---|---|---|
| 幂等 + attempts=3 | 超时/IO | TRANSIENT | 退避重试，最多 3 次；重试期间总墙钟超 timeoutMs 则执行器中断 |
| 幂等 + attempts=3 | 参数错误 | TERMINAL | 不重试 -> 转 `ToolExecutionException` -> processor 回填模型 |
| 非幂等 | 超时 | AMBIGUOUS | **绝不重试** -> 回填错误说明（状态 AMBIGUOUS） |
| 任意 | 超过 timeoutMs | — | 执行器中断 -> 回填 `tool_timeout` 错误，不影响兄弟工具 |
| 任意 | 重试耗尽 | TRANSIENT | 回填错误说明 |

## 8. 风险与取舍

1. **超时为总墙钟（含重试）**：实现简单、语义清晰；代价是 attempts 多但 timeout 短时可能只够跑 1 次。若后续需要"每次尝试独立超时"，可在 `GovernedToolCallback` 内再加 per-attempt 超时，执行器超时退化为兜底。
2. **默认不重试**：`default-max-attempts=1` 保证升级零行为变更，重试需显式声明幂等 + attempts，安全优先。
3. **失败转错误结果而非抛出**：与 Spring AI `DefaultToolCallingManager` 的"processor 兜底"思路一致，但更进一步把超时/未捕获异常也兜住，避免单工具炸整轮。代价是模型可能收到错误 JSON，需在 prompt 中约定模型如何解读。
4. **虚拟线程中断依赖下游响应中断**：纯 CPU 死循环不响应中断时超时无法真正停线程；这是虚拟线程 + 中断模型的固有局限，可接受（业务工具多为 IO）。
5. **落库延后**：`retryCount/failureCategory` 本期不写库，待 HIL 状态机统一接，避免与 memory 异步写入耦合。

## 9. 验证计划

- 单元测试：`ParallelToolExecutor` 超时/失败隔离（一个慢任务 + 一个快任务，慢任务超时后快任务结果仍返回）；`GovernedToolCallback` 重试矩阵（TRANSIENT 幂等重试、AMBIGUOUS 不重试、TERMINAL 不重试、重试耗尽）。
- 集成测试：demo 加一个 `@Tool` 模拟超时/抛 IO 异常，验证端到端模型收到错误说明后能继续对话，且同轮其它工具结果正常。
- 观测验证：`TOOL_CALL` span 覆盖整个重试过程，超时在 span 上体现为较长 duration + ERROR 事件。

---

> 待 zhaochen review 本方案（尤其：超时放执行器 vs 装饰器、默认是否重试、失败转错误结果 vs 抛出 三点取舍）后，再按 §6 改动清单落地。
