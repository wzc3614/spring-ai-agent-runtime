# Agent 编排接入方案设计

> 版：0.3（草案，**本期暂缓实施** — 2026-10-18 确认）
> 最后更新：2026-10-18
> 关联文档：`docs/design.md`（项目定位与模块分层）、`docs/TODO.md`（section I 记录暂缓项）

> **状态说明**：本设计的 §3（两层 ID 模型）与 §8（兼容性）已落地实现并端到端验证通过（见 `docs/TODO.md` section J）。§4–§7 的编排原语层（`TurnScope` / `TurnBuilder` / ChatClient 注册表 / langchain4j 接入等）为进阶能力，**本期不做**，已记入 `docs/TODO.md` section I（I1–I9，均标 `[-]` 暂缓）。当前 `AgentRuntime.call/stream` 单轮入口满足近期需求；待出现多步编排 / 跨 provider 切换 / langchain4j 接入等明确需求再启动。§9 的 6 个待确认项随原语层一并暂缓。

## 1. 背景与目标

当前 `AgentRuntime.call()/stream()` 是一个**封闭单轮管线**：它包办了「开 TURN span → set SpanContext → 建 `ChatClient` spec → 调用 → 记 FINAL_REPLY → 关 span」。用户无法在中间插入自己的步骤（RAG、改写、多步推理等），也无法用别的编排框架（如 langchain4j）来组织流程。

> 注：v0.2 之前的草案把入参叫 `sessionId`，实际它同时承担了"会话生命周期"和"记忆作用域"两个职责，是概念混淆。现已修正为两层 ID 模型（见 §3），调用签名改为 `call(uid, sessionId, userText)`。

本方案的目标：**把 `AgentRuntime` 从"封闭单轮入口"重构为"可复用 harness 原语 + 流式编排入口 + 默认入口"**，使用户能够：

1. 用 Spring AI 原生 `ChatClient` / advisor 体系自由编排多步流程，harness 自动落 span / 记忆 / 生命周期；
2. 用 langchain4j 的 `AiServices` / chain 体系编排，harness 以框架无关服务方式提供 observability 与记忆；
3. 在一个用户轮次内按步切换模型（便宜模型干简单活、贵模型做推理）；
4. 老用法（`agent.call(uid, sessionId, text)` 单轮）保持不变，退化为原语的默认组合。

## 2. 设计原则

1. **叠加而非替代**（与项目哲学一致）：harness 不重造编排器，只提供 span / 记忆 / 生命周期原语，编排逻辑由用户用 Spring AI 或 langchain4j 原生 API 编写。
2. **原语框架无关**：observability recorder、span context、记忆存储不绑定 Spring AI 的 `ChatModel`，langchain4j 用户也能直接调用。
3. **默认入口零迁移**：`call()/stream()` 保留，内部改为调用新原语，老调用方无感。
4. **流式与同步对称**：每个原语都同时提供同步与流式两种形态，span 上下文透传方式一致（`SpanContextHolder` + context-propagation）。
5. **API 分层**：按"想省事 → 想控制"分三档，样板代码（advisor、recordEvent、span 开关）只在最灵活档才需要手写。

## 3. 两层 ID 模型与 TURN 语义

### 3.1 两层 ID：uid → sessionId

调用入参由原来的单 `sessionId` 拆成两层，各司其职：

| ID | 语义 | 作用域 | 落库 |
|---|---|---|---|
| `uid` | 用户 ID（谁） | 跨会话 | `agent_session.user_id` |
| `sessionId` | 会话 ID（生命周期 + 记忆作用域：一个用户的一次 agent 连接，有 START/ENDED，记忆按 session 共享累积） | 一个 session 可含多个 turn | `agent_session` PK、`agent_message.session_id`、`agent_runtime_obs.span.session_id` |
| `turnId` | 轮次 ID（一轮：一次用户输入→最终回复，内部可多步） | 一个 session 可含多个 turn | `agent_message` 内部 turnId、TURN span |

要点：
- **记忆按 session 共享**：`CompactingChatMemory` 以 `sessionId` 为 key（Spring AI `ChatMemory.CONVERSATION_ID` param 传 sessionId），同 session 跨调用共享同一份记忆、累积。
- **seq 按 session 作用域分配**：`UNIQUE(session_id, seq)`，seq 在 session 内单调递增。
- **turnId 内部生成**：每轮（一次 `call`/`stream`）内部生成一个 turnId，不暴露到公开签名；同 turn 内多步（多次 LLM 调用 + 工具调用）共用一个 turnId。
- **不再引入 conversationId**：业界对 conversation 语义不统一（部分框架 conversation≈session，部分 conversation≈turn）。我们内部已有 turnId 表达"一轮"，再造 conversationId 会与之重复或混淆。若未来确有"会话内子对话分组"需求，再单独引入。

### 3.2 TURN 不是"步骤"，是"一轮用户交互"

**TURN = 一次用户输入 → 产出一个最终回复的完整轮次**，不是"一个 LLM 步骤"。一个 TURN 内部可以有**多次** LLM 调用 + **多次**工具调用：

- 现有 `call()` 里一个 TURN 就包含了「LLM 调用 → 工具 → 再 LLM」的整个循环。
- `ObservingChatModel` 每次被调都会自动建一个 **MODEL_CALL 子 span**，工具装饰器自动建 **TOOL_CALL 子 span**，它们都挂到当前 TURN 下。
- 因此多步编排的正确写法是：**一个 `TurnScope` 包住整个用户轮次，里面随便调多少次 `chatClient`，每次自动落 MODEL_CALL 子 span**，不需要每步开一个 TurnScope。

> 反例（不要这样写）：每个 LLM 调用各开一个 `TurnScope` —— 会让语义错乱、span 树变成平铺而非嵌套。

## 4. 原语拆分

把 `AgentRuntime` 内部能力拆成以下可独立调用的原语。除 `TurnScope` / `TurnBuilder` / ChatClient 注册表是新增外，其余基本是把 `private` 改公开或加 getter。

| 原语 | 形态 | 作用 | 现状 |
|---|---|---|---|
| `openTurn(uid, sessionId): TurnScope` | AutoCloseable | 幂等启动 session，开 TURN span + set `SpanContextHolder`（两参），close 时清理 + 关 span | 新增（逻辑现散在 `call()` 内） |
| `instrumentTurn(uid, sessionId, Flux)` | Flux 装饰器 | 流式 TURN 的对称版（defer + doFinally + contextWrite） | 新增 |
| `turn(uid, sessionId): TurnBuilder` | 流式入口 | 一行完成 openTurn + 记忆 advisor + 调用 + FINAL_REPLY + close | 新增 |
| `chatClient()` / `chatClient(name)` | getter | 返回已装饰的 `ChatClient`，按名取支持多 provider | `chatClient` 现为 `private`，改公开 + 加注册表 |
| `chatFor(sessionId)` | 工厂 | 返回已默认挂记忆 advisor、绑好 `sessionId`（作为 `ChatMemory.CONVERSATION_ID`）的 `ChatClient`（记忆按 session 共享） | 新增 |
| `recorder()` | getter | 返回 `ObservabilityRecorder`，供用户记自定义事件 | 现无 getter |
| `memory()` | getter | 返回 `CompactingChatMemory` | 已有 `getMemory()` |
| `ensureStarted(uid, sessionId)` | 公开方法 | 幂等开 SESSION span + 触发 START 生命周期 | 现为 `private` |
| `close(sessionId)` | 公开方法 | 触发 ENDED + 关 SESSION span | 已公开 |

### 4.1 `TurnScope`

```java
/**
 * 一个 TURN span 的作用域。try-with-resources 包住一整轮用户交互，
 * 自动开/关 TURN span 并设置/清理 SpanContextHolder。
 * 内部可包含任意多次 chatClient 调用与工具调用，均自动挂为本 TURN 的子 span。
 */
public class TurnScope implements AutoCloseable {
    private final Span turnSpan;
    private final ObservabilityRecorder recorder;

    TurnScope(Span turnSpan, ObservabilityRecorder recorder) {
        this.turnSpan = turnSpan;
        this.recorder = recorder;
    }

    /** 当前 TURN 的 span id，供用户记事件或挂子 span。 */
    public String spanId() {
        return turnSpan.getSpanId();
    }

    @Override
    public void close() {
        SpanContextHolder.clear();
        recorder.endSpan(turnSpan.getSpanId());
    }
}
```

`AgentRuntime` 上：

```java
/**
 * 开启一个 TURN 作用域。幂等确保 session（带 uid）已启动，开 TURN span
 * 并设置 SpanContextHolder（parentSpanId / sessionId 两参）。
 * 调用方必须 try-with-resources 关闭，否则 span 不会结束。
 */
public TurnScope openTurn(String uid, String sessionId) {
    ensureStarted(uid, sessionId);
    Span turn = recorder.startSpan(SpanTypeEnum.TURN, sessionSpanIds.get(sessionId), sessionId);
    SpanContextHolder.set(turn.getSpanId(), sessionId);
    return new TurnScope(turn, recorder);
}
```

### 4.2 流式 `instrumentTurn`

```java
/**
 * 为一个流式 TURN 提供装饰器：幂等启动 session，开 TURN span、set SpanContext（两参）、
 * 累积 token、complete 时记 FINAL_REPLY、terminate 时清理 + 关 span、contextWrite 写入 Reactor Context。
 * 用户把自己的 Flux 链传入，返回已埋点的 Flux。
 */
public Flux<String> instrumentTurn(String uid, String sessionId, Flux<String> source) {
    return Flux.defer(() -> {
        ensureStarted(uid, sessionId);
        Span turn = recorder.startSpan(SpanTypeEnum.TURN, sessionSpanIds.get(sessionId), sessionId);
        SpanContextHolder.set(turn.getSpanId(), sessionId);
        StringBuilder acc = new StringBuilder();
        return source
                .doOnNext(acc::append)
                .doOnComplete(() -> recorder.recordEvent(turn.getSpanId(),
                        EventTypeEnum.FINAL_REPLY, acc.toString()))
                .doFinally(s -> {
                    SpanContextHolder.clear();
                    recorder.endSpan(turn.getSpanId());
                })
                .contextWrite(Context.of(
                        SpanContextHolder.CONTEXT_KEY_PARENT_SPAN_ID, turn.getSpanId(),
                        SpanContextHolder.CONTEXT_KEY_SESSION_ID, sessionId));
    });
}
```

### 4.3 `TurnBuilder`（最省事入口）

把"openTurn + 记忆 advisor + 调用 + FINAL_REPLY + close"全收进流式 builder，用户一行完成：

```java
public class TurnBuilder {
    private final AgentRuntime rt;
    private final String uid;
    private final String sessionId;
    private String userText;
    private List<ToolCallback> tools;
    private String clientName;     // 选 ChatClient，默认主 client
    private String modelName;      // 覆盖模型名，可选
    private Consumer<ChatClient.ChatClientRequestSpec> customizer;  // 高级兜底

    public TurnBuilder user(String text) { this.userText = text; return this; }
    public TurnBuilder tools(List<ToolCallback> t) { this.tools = t; return this; }
    public TurnBuilder model(String m) { this.modelName = m; return this; }
    public TurnBuilder chatClient(String name) { this.clientName = name; return this; }
    public TurnBuilder customize(Consumer<ChatClient.ChatClientRequestSpec> c) { this.customizer = c; return this; }

    /** 同步：自动 openTurn + 挂记忆 advisor + 调用 + 记 FINAL_REPLY + close。 */
    public String call() { /* 内部组合 */ }

    /** 流式：自动 instrumentTurn + 挂记忆 advisor + 累积 + 记 FINAL_REPLY。 */
    public reactor.core.publisher.Flux<String> stream() { /* 内部组合 */ }
}
```

`AgentRuntime` 上：

```java
public TurnBuilder turn(String uid, String sessionId) {
    return new TurnBuilder(this, uid, sessionId);
}
```

### 4.4 `chatFor(sessionId)`（省掉 advisor 样板）

返回一个**已默认挂好记忆 advisor、绑好 `sessionId`（作为 `ChatMemory.CONVERSATION_ID`）** 的 `ChatClient`，省掉每次 `.advisors(a -> a.param(...))`。记忆按 session 共享：

```java
/** 返回带记忆 advisor 默认值的 ChatClient（sessionId 作为记忆 key 已绑）。 */
public ChatClient chatFor(String sessionId) { ... }
```

### 4.5 ChatClient 注册表（支持多 provider 切换）

当前 `AgentRuntime` 只持有一个 `chatClient`。要支持"一个 TurnScope 内按步切换不同 provider 的模型"，加一个**可注册多 ChatClient 的注册表**，按名字取，且每个都被 `ObservingChatModel` 装饰过（自动落 span）：

```java
// 装配时注册多个模型（用户侧 @Configuration）
@Bean ChatClient cheapClient(...) { ... }       // qwen-turbo
@Bean ChatClient reasoningClient(...) { ... }  // qwen3.6-plus

// 用时按名取，仍在同一个 TurnScope 内
runtime.chatClient("reasoningClient").prompt()...
```

**关键点**：`SpanContextHolder` 是 TurnScope 级别设的（两参：parentSpanId / sessionId），无论调哪个 `ChatClient`，其 MODEL_CALL span 都挂到当前 TURN 下。**切换模型对 observability 完全透明**，无需额外设计。跨 provider 的记忆共享依赖 `CompactingChatMemory` 按 `sessionId` 存（`ChatMemory.CONVERSATION_ID` 传 sessionId）、与 model 无关，天然支持。

### 4.6 `call()/stream()` 退化为默认组合

改造后 `call()` 等价于：

```java
public String call(String uid, String sessionId, String userText) {
    try (TurnScope turn = openTurn(uid, sessionId)) {
        ChatClient.ChatClientRequestSpec spec = chatClient.prompt().user(userText);
        if (!CollectionUtils.isEmpty(tools)) {
            spec = spec.toolCallbacks(tools);
        }
        String reply = spec
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .call().content();
        recorder.recordEvent(turn.spanId(), EventTypeEnum.FINAL_REPLY, reply);
        return reply;
    }
}
```

`stream(uid, sessionId, userText)` 等价于把 `instrumentTurn` 套到默认 `ChatClient` 流上。老调用方签名需补 `uid`（破坏性，已确认）。

## 5. API 分层（用户按需选）

| 档 | API | 适用 | 样板量 |
|---|---|---|---|
| 最省事 | `runtime.turn(uid, sessionId).user(text).tools(t).call()` / `.stream()` | 单轮或每步独立 TURN，默认记忆+埋点全包 | 0 |
| 中等 | `runtime.openTurn(uid, sessionId)` + `runtime.chatFor(sessionId).prompt()...` | 一个 TURN 内多步、共享记忆、自定义中间逻辑 | 仅写业务逻辑 |
| 最灵活 | `runtime.openTurn(uid, sessionId)` + `runtime.chatClient(name).prompt()...` + `runtime.recorder().recordEvent(...)` | 多 provider 切换、自定义事件、完全掌控 | 全手写 |

样板代码（advisor、recordEvent、span 开关）只在最灵活档才需要手写；前两档全自动。

## 6. Spring AI 编排示例

### 6.1 最省事档：单轮

```java
String reply = runtime.turn(uid, sessionId).user(text).tools(tools).call();
reactor.core.publisher.Flux<String> flux =
        runtime.turn(uid, sessionId).user(text).tools(tools).stream();
```

### 6.2 中等档：一个 TURN 内多步、按步切模型名（同 provider）

```java
/**
 * 一个用户轮次内：便宜模型改写查询 → RAG 检索 → 贵模型带工具生成最终回答。
 * 整轮一个 TURN span，内部 2 个 MODEL_CALL 子 span + N 个 TOOL_CALL 子 span。
 */
public String research(String uid, String sessionId, String question) {
    try (TurnScope t = runtime.openTurn(uid, sessionId)) {
        // 第 1 步：便宜模型改写
        String rewritten = runtime.chatFor(sessionId).prompt()
                .user("把以下问题改写成检索查询：" + question)
                .options(o -> o.model("qwen-turbo"))
                .call().content();

        // 第 2 步：RAG 检索（不调 LLM，不产生 span）
        String context = ragAdvisor.retrieve(rewritten);

        // 第 3 步：贵模型带工具生成最终回答
        String reply = runtime.chatFor(sessionId).prompt()
                .user("根据资料回答：" + question + "\n资料：" + context)
                .options(o -> o.model("qwen3.6-plus"))
                .toolCallbacks(tools)
                .call().content();

        runtime.recorder().recordEvent(t.spanId(), EventTypeEnum.FINAL_REPLY, reply);
        return reply;
    }
}
```

### 6.3 最灵活档：一个 TURN 内跨 provider 切换

```java
public String researchCrossProvider(String uid, String sessionId, String question) {
    try (TurnScope t = runtime.openTurn(uid, sessionId)) {
        // OpenAI 改写
        String rewritten = runtime.chatClient("openaiClient").prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .user("改写：" + question).call().content();
        // 自部署贵模型推理
        String reply = runtime.chatClient("reasoningClient").prompt()
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .user("推理：" + rewritten).toolCallbacks(tools).call().content();
        runtime.recorder().recordEvent(t.spanId(), EventTypeEnum.FINAL_REPLY, reply);
        return reply;
    }
}
```

### 6.4 同 session 多轮对话：记忆跨轮累积

```java
// 同一个 session 下连续多轮对话，记忆按 session 共享累积
String r1 = runtime.turn(uid, sessionId).user("记住我叫张三").call();
String r2 = runtime.turn(uid, sessionId).user("我叫什么？").call(); // 知道张三（同 session 共享记忆）
// 每轮内部各自生成 turnId，TURN span 各自独立；记忆跨轮持久化
```

## 7. langchain4j 编排示例

langchain4j 有自己的 `ChatLanguageModel` / `AiServices`，与 Spring AI 的 `ChatModel` 是两套。harness 的 observability 现在装饰的是 Spring AI `ChatModel`，**不能直接套到 langchain4j 的 model 上**。提供两条接入路：

### 7.1 路 1（推荐，解耦）：框架无关服务 + 手动埋点

harness 把 `ObservabilityRecorder` / `SpanContextHolder` / `CompactingChatMemory` 作为框架无关服务暴露，用户在 langchain4j 的 `AiService` 调用外层用 `TurnScope` 手动埋点。

```java
@Service
public class Lc4jResearchWorkflow {

    private final AgentRuntime runtime;
    private final ResearchAgent lc4jAgent;     // langchain4j AiServices 构建

    public Lc4jResearchWorkflow(AgentRuntime runtime, ChatLanguageModel lc4jModel,
                                List<Object> lc4jTools, ChatMemoryStore store) {
        this.runtime = runtime;
        // langchain4j 的记忆存储适配到 harness 的 CompactingChatMemory（按 sessionId 作用域，记忆按 session 共享）
        ChatMemoryProvider memoryProvider = sessionId ->
                lc4jMemoryFromHarness(runtime.memory(), sessionId, store);
        this.lc4jAgent = AiServices.builder(ResearchAgent.class)
                .chatLanguageModel(lc4jModel)
                .tools(lc4jTools.toArray())
                .chatMemoryProvider(memoryProvider)
                .build();
    }

    public String research(String uid, String sessionId, String question) {
        // 用 harness 的 TurnScope 包住整个 langchain4j 编排
        try (TurnScope turn = runtime.openTurn(uid, sessionId)) {
            String reply = lc4jAgent.research(question);
            runtime.recorder().recordEvent(turn.spanId(), EventTypeEnum.FINAL_REPLY, reply);
            return reply;
        }
    }
}
```

说明：
- langchain4j 的 `AiServices` 内部多步调用（工具循环、思维链）对 harness 不可见，harness 只在 TURN 粒度埋点；如需更细粒度，需用户在 `AiService` 实现里手动开子作用域，或走路 2。
- 记忆通过 `ChatMemoryStore` 适配层桥接到 harness 的 `CompactingChatMemory`，复用溢出压缩能力。

### 7.2 路 2（重，不推荐）：model 适配器

写一个 `LangChain4jChatModelAdapter` 把 langchain4j 的 `ChatLanguageModel` 适配成 Spring AI `ChatModel`，再套 `ObservingChatModel`，从而复用自动埋点。

```java
// 示意，非真实代码
public class LangChain4jChatModelAdapter implements ChatModel {
    private final ChatLanguageModel delegate;   // langchain4j
    @Override
    public ChatResponse call(Prompt prompt) {
        // 转换 Spring AI Message -> langchain4j ChatMessage
        // 调用 delegate.chat(...)
        // 转换 langchain4j Response -> Spring AI ChatResponse
    }
    // 流式同理
}
```

代价：要实现两套消息协议、工具协议、流式协议互转，维护成本高，且 langchain4j 与 Spring AI 版本演进时容易踩坑。**仅当用户确实需要 langchain4j 内部每步自动落 span 时才考虑**，一般情况走路 1。

## 8. 改造影响与兼容性

| 项 | 改造前 | 改造后 | 兼容性 |
|---|---|---|---|
| `agent.call(...)` / `agent.stream(...)` | `(sessionId, text)` 单 ID | `(uid, sessionId, text)` 两层 ID | **破坏性**：老调用方需补 uid（已确认） |
| `chatClient` 字段 | `private` 单例 | 公开 `chatClient()` / `chatClient(name)` + 注册表 | 新增公开 API |
| `recorder` 字段 | `private` | 公开 `recorder()` getter | 新增公开 API |
| `ensureStarted` | `private` `(sessionId)` | 公开 `(uid, sessionId)` | 新增公开 API |
| `TurnScope` / `TurnBuilder` / `instrumentTurn` / `chatFor` / `turn` | 不存在 | 新增 | 纯新增 |
| `agent_session.user_id` | 列存在但从不写入 | 现写入 uid | 修复 bug，无破坏 |
| 记忆作用域 | （隐式按 sessionId） | 显式按 sessionId 共享累积；`ChatMemory.CONVERSATION_ID` 传 sessionId | 行为不变，仅显式化 |
| `agent_message` 唯一约束 | `UNIQUE(session_id, seq)` | 保持 `UNIQUE(session_id, seq)`（seq 按 session 作用域） | 无破坏 |

结论：除调用签名从单 ID 改为两层 ID（破坏性，已确认）外，其余全部是新增公开 API + 内部重构。schema 无需迁移（V4 已删除，回到 V3 状态）。

## 9. 待 review 与待确认项（随原语层一并暂缓，本期不做）

> 两层 ID 模型（uid → sessionId）与 `call(uid, sessionId, text)` 签名已确认并落地实现（见 §8）。以下 6 项均属尚未实现的编排原语层，**本期暂缓**（见 `docs/TODO.md` section I），待启动原语层时再逐项确认。

1. **`TurnScope` 放 `core` 还是下沉接口到 `api`？** 倾向放 `core`（依赖 `SpanContextHolder`）。若希望 langchain4j 用户只依赖 `api` 就能用，需把 `TurnScope` 接口下沉到 `api`、实现留 `core`。请确认。
2. **`instrumentTurn` 命名**：候选 `streamTurn` / `wrapTurn` / `instrumentTurn`，倾向 `instrumentTurn`。
3. **ChatClient 注册表的注册方式**：自动收集所有 `ChatClient` bean 按 bean 名注册，还是要求用户显式声明一个 `ChatClientRegistry`？倾向自动收集 + `@Primary` 标主 client。
4. **langchain4j 记忆适配层**是否纳入本期范围，还是只出接口、适配由用户自写？倾向后者。
5. **是否需要 `WorkflowTemplate` 抽象**（封装"多 TURN + 记忆 + 事件"脚手架），还是只出原语让用户自由组合？倾向先只出原语，模板后续按需加。
6. **`TurnBuilder` 是否支持"一个 TURN 内多步"**：当前 `TurnBuilder` 是单步单轮语义；多步需用 `openTurn` + `chatFor`。是否要给 `TurnBuilder` 加 `step()` 链式多步 API？倾向不加（保持单步），多步用中等档，避免 builder 变复杂。

## 10. 后续工作（不在本期）

- `WorkflowTemplate` 脚手架（若原语组合样板代码重复过多再抽）。
- langchain4j model 适配器（路 2，按需）。
- 编排流程的可视化回放（多 TURN span 树在 observability UI 上的展示）。
