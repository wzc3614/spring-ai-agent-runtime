# Agent Runtime

> 一个挂在 Spring AI 之上的 Harness 层，聚焦"单 Agent 内部活得好、活得久、可复盘"。
> 状态：0.1（最小可用已端到端跑通）

## 定位

**AgentRuntime 不重造 Spring AI，也不跟编排层抢位置**——它是一层"运行时治理"，挂在 Spring AI 与业务 Agent 之间，只管单个 Agent 内部怎么活下去：记忆不漂移、大报文不击穿、推理可复盘、能力可热插拔。

落地形态是**嵌入式 Spring Boot Starter**：任意 Spring Boot + Spring AI 项目引入依赖即可用，不需要新搭一套平台。

- 技术栈：Java 21 + Spring Boot 3.4 + Spring AI 1.0.0 GA
- 协议：Apache License 2.0
- 基础设施全开源可插拔（PostgreSQL/MySQL、Redis、Nacos/Apollo、OpenTelemetry）

## 核心设计

### 四条哲学
1. **叠加而非替代**：原样复用 Spring AI 的 `ChatClient`/`@Tool`/`ChatMemory`/Observation，能力以 Advisor/装饰器方式挂上去。
2. **读写分离，把复杂度推后**：写入毫秒级、零加工 append，压缩/预算决策推迟到注入模型前一刻。
3. **信息不丢弃，只降级**：面对上下文压力，从原文渐进降级到摘要，不是断崖截断。
4. **默认安全，业务无感**：异常清理、并发一致性、悬空调用修复由框架兜底。

### 模块分层
```
api          契约层（domain/enums/repository/spi/lifecycle，零 Spring AI）
core         Spring AI 感知共享内核（parallel/safety/tool 元数据，不依赖子系统）
memory       记忆（持久化 + 异步写入 + 工具结果压缩 + 动态预算）
observability 认知可观测（Span/Event 树 + 日志/JDBC Sink）
capability   能力供给（Skill + MCP 热插拔，MCP 未做）
starter      自动装配 + AgentRuntime 编排器
demo         端到端示例
```

### 核心能力
- **记忆**：异步两级写入（Redis FIFO 队列 + Cron worker 批量落库，幂等 + 分布式锁 + DLQ），读取侧 DB + 队列合并 + 动态预算 + 微压缩。
- **可观测**：Span/Event 树（Session→Turn→ModelCall/ToolCall），日志 sink 永远在线，JDBC sink 条件装配，usage/finishReason 落 span 属性。
- **安全**：悬空调用检测修复、会话生命周期清理、工具失败分类（TRANSIENT/TERMINAL/AMBIGUOUS）+ 幂等感知重试。

详见 `docs/design.md`，待办见 `docs/TODO.md`，bugfix 见 `docs/bugfix-notes.md`。

## 怎么跑通 demo

### 前置
- JDK 21、Maven、Docker（或本机已有 PostgreSQL + Redis）
- 一个 OpenAI 兼容的模型网关 key（国内各家大模型网关基本都兼容）

### 1. 起基础设施
```bash
docker compose up -d          # 拉起 PostgreSQL + Redis
docker compose ps            # 确认都 healthy
```
默认连接：PG `localhost:5432/agent_runtime`（postgres/postgres），Redis `localhost:6379`。

### 2. 配模型网关
复制 `agent-runtime-demo/src/main/resources/application-local.yml.example` 为 `application-local.yml`，填入你的网关：
```yaml
spring:
  ai:
    openai:
      api-key: sk-你的key
      base-url: https://你的网关           # 注意：不要带末尾 /v1，Spring AI 会自动追加
      chat:
        options:
          model: 你的模型名
```
> `application-local.yml` 含 key，建议加进 `.gitignore` 不提交。

### 3. 构建
```bash
mvn -DskipTests package
```

### 4. 启动
```bash
SPRING_PROFILES_ACTIVE=local \
  java -jar agent-runtime-demo/target/agent-runtime-demo-0.1.0-SNAPSHOT.jar
```
看到 `Started AgentRuntimeDemoApplication` 即就绪（默认 8080 端口）。

### 5. 对话验证
```bash
# 第 1 轮：触发工具调用
curl -s -X POST "http://localhost:8080/chat" \
  --data-urlencode "sessionId=s1" \
  --data-urlencode "message=帮我查一下用户u123的信息和最近的订单"

# 第 2 轮：验证跨轮记忆
curl -s -X POST "http://localhost:8080/chat" \
  --data-urlencode "sessionId=s1" \
  --data-urlencode "message=他最近订单状态怎么样"

# 结束会话
curl -s -X POST "http://localhost:8080/chat/close" \
  --data-urlencode "sessionId=s1"
```
> 注意：中文必须用 `--data-urlencode`（直接写中文会触发 HTTP 400）。

### 6. 看观测数据
```bash
# span 树
docker exec agent-runtime-postgres psql -U postgres -d agent_runtime -c \
  "SELECT span_type, count(*) FROM agent_runtime_obs.span WHERE session_id='s1' GROUP BY span_type ORDER BY 1;"

# LlmCallSpan 的 usage/finishReason
docker exec agent-runtime-postgres psql -U postgres -d agent_runtime -c \
  "SELECT attributes FROM agent_runtime_obs.span WHERE span_type='MODEL_CALL';"

# 工具调用事件
docker exec agent-runtime-postgres psql -U postgres -d agent_runtime -c \
  "SELECT event_type, count(*) FROM agent_runtime_obs.event_record e \
   JOIN agent_runtime_obs.span s ON s.span_id=e.span_id \
   WHERE s.session_id='s1' GROUP BY event_type ORDER BY 1;"

# 消息落库（异步，发完请求等 1~2 秒再查）
docker exec agent-runtime-postgres psql -U postgres -d agent_runtime -c \
  "SELECT role, count(*) FROM agent_message WHERE session_id='s1' GROUP BY role ORDER BY role;"
```

### 预期结果
- 第 1 轮回复包含工具真实返回；第 2 轮能引用上一轮；
- span 树：`SESSION(1) → TURN(2) → MODEL_CALL(2) + TOOL_CALL(2)`；
- `MODEL_CALL.attributes` 含 `modelName/inputTokens/outputTokens/finishReason`；
- `event_record` 含 `FINAL_REPLY/TOOL_INPUT/TOOL_OUTPUT`；
- `agent_message` 含 `user/assistant/tool` 记录。

## 环境管理
```bash
docker compose ps        # 状态
docker compose stop      # 停（保留数据）
docker compose start     # 再起
docker compose down      # 删容器（保留 volume）
docker compose down -v    # 连数据卷一起删（清空 DB）
```

## 文档
- `docs/design.md` — 整体设计与各模块设计
- `docs/TODO.md` — 待办与状态
- `docs/bugfix-notes.md` — 关键 bugfix 过程
