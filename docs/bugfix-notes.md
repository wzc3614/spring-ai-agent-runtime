# Bugfix 记录

> 记录端到端验证过程中遇到的关键 bugfix 过程
> 最后更新：2026-10-17

## Bug 1：MessageRoleEnum code 大小写不匹配

### 现象
实跑 demo，`POST /chat` 返回 HTTP 500，日志：
```
java.lang.IllegalArgumentException: unknown MessageRoleEnum code: user
```

### 定位
`CompactingChatMemory.add()` 里 `toAgentMessage(task)` 调 `MessageRoleEnum.of(task.getRoleCode())` 抛异常，发生在请求线程（`nio-8080-exec-1`）。

### 根因
- 我们的 `MessageRoleEnum` code 用大写：`SYSTEM("SYSTEM")`、`USER("USER")`…
- Spring AI 的 `MessageType.getValue()` 返回**小写**：`system`/`user`/`assistant`/`tool`
- `add()` 存 `roleCode = message.getMessageType().getValue()` = 小写 `user`，落库后 `of("user")` 找不到（我们 enum code 是大写 `USER`）。

### 修复
`MessageRoleEnum` 的 code 改为小写，对齐 Spring AI：
```java
SYSTEM("system", "system"),
USER("user", "user"),
ASSISTANT("assistant", "assistant"),
TOOL("tool", "tool");
```
同步 `MessageConverter.toMessage` 的 switch 改为小写 case。DDL 注释里 role 本就是小写，符合设计意图。

### 教训
凡是由 Spring AI 外部值驱动的枚举 code，必须对齐 Spring AI 的实际返回值，不能自己定大小写。

---

## Bug 2：event_record.payload 列 jsonb 拒绝非 JSON 文本

### 现象
修完 Bug 1 后重跑，span 落库成功，但 event 落库失败，日志：
```
ERROR: invalid input syntax for type json
SQL [INSERT INTO agent_runtime_obs.event_record (...) VALUES (?, ?, ?, ?::jsonb, ?) ...]
```

### 根因
- V1 schema 把 `event_record.payload` 建成 `JSONB`。
- 但 `EventRecord.payload` 是异构的：`FINAL_REPLY` 是纯文本回复（中文），`TOOL_INPUT` 是工具参数 JSON，`TOOL_OUTPUT` 是工具返回（任意）。
- `JdbcEventRepository` 用 `?::jsonb` 强转，纯文本不是合法 JSON → 转换失败。

### 修复
- V3 迁移把列改成 `text`：
```sql
ALTER TABLE agent_runtime_obs.event_record ALTER COLUMN payload TYPE text USING payload::text;
```
- `JdbcEventRepository` 的 INSERT 去掉 `?::jsonb`，直接按 text 写。

### 教训
JSONB 列只适合"确定是 JSON"的数据；异构载荷（纯文本/任意串）用 text 更诚实，避免强转失败。span 的 `attributes` 是确定 JSON（ObjectMapper 序列化），保持 jsonb。

---

## Bug 3：event 外键违反（span 未落库就写 event）

### 现象
修完 Bug 2 后重跑，event 仍 0 行，日志：
```
ERROR: insert or update on table "event_record" violates foreign key constraint "event_record_span_id_fkey"
```

### 根因
- `SpanTreeObservabilityRecorder.recordEvent()` 原实现：创建 `EventRecord` 后**立即** `writeEvent` 下发各 sink。
- 但父 span 要等 `endSpan` 才 `writeSpan` 落库。
- 所以 event 先于 span 写库 → `event_record.span_id` 的 FK 找不到父 span → 违反。

### 修复
在 recorder 里**缓冲 event**，span 结束时先写 span 再写它的 event：
```java
recordEvent(spanId, type, payload):
    pendingEvents.computeIfAbsent(spanId, k -> new ArrayList<>()).add(event);  // 不下发

endSpan(spanId):
    span.setEndedAt(now);
    for sink: sink.writeSpan(span);           // 先写 span
    events = pendingEvents.remove(spanId);
    for e in events: for sink: sink.writeEvent(e);   // 再写 event（FK 已存在）
```

### 教训
有外键依赖的写入顺序要由生产方控制：被依赖者先写，依赖者后写。立即下发看似"实时"，但破坏了 FK 顺序。缓冲到 span 结束再统一写，既保 FK 又不丢事件。

---

## Bug 4：工具不下发（ChatOptions vs OpenAiChatOptions）—— 最关键的一个

### 现象
前 3 个 bug 修完后，对话和记忆都通了，但**模型不调工具**：回复里的订单号是模型编的（ORD20240520001），不是我们 `BusinessTool` 返回的（ORD-1001）；span 树�里 0 个 `TOOL_CALL`；`MODEL_CALL` 的 `finishReason=STOP`（不是 `tool_calls`）。

### 排查过程

**第 1 步：抓真实请求体**（用户问"把你调用 chat 端口的请求列出来"）

用 `nc -lk 9999` 抓本地请求 → 只抓到 headers，没抓到 body（Java 21 HttpClient 走 HTTP/2 升级 + chunked + 等 100-continue，nc 没回）。改用 Python `http.server` 手写 chunked 读取，抓到完整 body：
```json
{
  "messages": [{"role":"system",...}, {"role":"user","content":"帮我查一下用户u123..."}],
  "model": "qwen3.6-plus",
  "stream": false,
  "temperature": 0.7
}
```
**请求体里没有 `tools` 字段** —— 模型根本没收到工具定义，所以无从调用。

**第 2 步：隔离变量**

依次对照实验：
1. 去掉 `ObservingChatModel` 包装 → 仍无 tools → 不是 ChatModel 包装的锅。
2. 去掉工具装饰器、`builder.defaultTools(Object[])` → 仍无 tools；
3. 按 prompt 传 `tools(List<ToolCallback>)`（包装后） → 仍无 tools；
4. 按 prompt 传原始 `Object[]` → 仍无 tools；
5. `builder.defaultTools(ToolCallbackProvider)` → 仍无 tools。

所有路径都没 tools，说明不是我们的装饰器/传递方式问题。

**第 3 步：反编译 Spring AI M6 源码**

用户提供了 `DefaultChatClient.tools(Object...)` 源码：
```java
public ChatClientRequestSpec tools(Object... toolObjects) {
    this.functionCallbacks.addAll(Arrays.asList(ToolCallbacks.from(toolObjects)));
    return this;
}
```
证实 `tools(Object...)` **会**把工具加进 `functionCallbacks`。`tools(List<ToolCallback>)` 字节码也证实它 `functionCallbacks.addAll(toolCallbacks)`。所以工具确实进了 `functionCallbacks`，但没进请求。

**第 4 步：定位根因**

`functionCallbacks`（spec 上）在 `.call()` 构建 `Prompt` 时要转移到 `ChatOptions`。但 demo 用：
```java
.defaultOptions(ChatOptions.builder().temperature(0.7).build())
```
`ChatOptions.builder()...build()` 返回的是**通用 `ChatOptions`**（非 `OpenAiChatOptions`），**不携带工具回调**。`OpenAiChatModel.call(Prompt)` 期望从 `OpenAiChatOptions.getToolCallbacks()` 取工具，通用 `ChatOptions` 没有 → 工具丢失。

### 修复
demo 改用 `OpenAiChatOptions`：
```java
.defaultOptions(OpenAiChatOptions.builder().temperature(0.7).build())
```
`OpenAiChatOptions` 实现 `ToolCallingChatOptions`，携带工具回调 → 工具经 `ChatOptions` 正确下发到请求的 `tools` 字段。框架恢复按 prompt 传包装后的 `List<ToolCallback>`。

### 验证
重抓请求 → `tools` 字段出现（`query_user_info`/`query_recent_orders`）。实跑 → 模型真的调工具，回复里是我们 `BusinessTool` 的真实数据（`Zhang San`/`ORD-1001`/`ORD-1002`），span 树出现 2 个 `TOOL_CALL` + `TOOL_INPUT`/`TOOL_OUTPUT`。

### 教训
- Spring AI 1.0.0-M6 处于 `ToolCallback`(新)/`FunctionCallback`(旧) 过渡期，工具回调走 `ChatOptions`，**通用 `ChatOptions` 不携带**，必须用具体模型的 `ChatOptions`（如 `OpenAiChatOptions`）。
- 框架不能假设用户传的 `ChatOptions` 一定携带工具；文档要提醒使用者：涉及工具调用时，`defaultOptions` 必须用具体模型的 `ChatOptions`。

---

## 复盘：抓请求体是最有效的排障手段

Bug 4 的定位关键在于"把发给大模型网关的真实 HTTP 请求体列出来看"。光看代码/日志只能猜，抓到请求体（`tools` 字段有无）一锤定音。过程中 `nc` 抓不到 chunked body，临时写了个 Python `http.server` 读 chunked，是值得记的小技巧。

> 经验：端到端验证时，遇到"行为不符预期"，优先想办法**抓真实 IO**（HTTP 请求/响应、SQL、Redis 命令），比读代码猜根因高效得多。
