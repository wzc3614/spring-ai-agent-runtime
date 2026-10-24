## Capability模块设计（Skill相关）

## 定位

Skill模块是 Tool之上的“能力编排层”，而不是取代 Tool 的“调用层”。一个Skill 可以内部引用多个 ToolCallback，但对外只呈现为一个带有边界描述的语义单元。

### 一、Skill的定义规范与注册表

本Harness 需要为 Skill 定义一个夸存储可移植的契约，而不是绑定在 classpath 或某个文件系统上。推荐的最小契约：

```java
class SkillDescriptor{
    String skillId;
    String name;
    String description;       // 触发匹配的核心依据
    List<String> notFor;       // 负例边界，防止误触发
    List<String> toolRefs;     // 引用的 ToolCallback 名称
    String instructionRef;     // 指令正文的存储位置（惰性加载）
    Map<String,String> meta;   // 版本、owner、风险等级
}
```

notFor 字段非常重要，作为badcase能够帮助提升准确率。description 告诉模型“何时用”，notFor 告诉模型"何时不要用"

注册表 `SkillRegistry` 负责扫描和持有 `SkillDescriptor`，来源应可插拔：classpath、Git、工作区目录、Nacos 或数据库。harness 只依赖注册表接口，不绑定具体来源。注册时校验 `toolRefs` 是否都能解析到已注册的 `ToolCallback`，悬空引用直接拒绝注册，而不是等到调用时才失败。

SkillRegistry 每次路由前检查 SkillDescriptor 的版本或内容哈希是否变化，变化时重新加载元数据。元数据可缓存，但正文不应缓存

### 二、渐进式加载的pipeline

1. 元数据注入（会话启动或Skill集合变更时）：将所有 SkillDescriptor 的 name + description + notFor 格式化为一段紧凑地注入文本，放入 System Prompt 的固定位置，其他内容一概不进入上下文
2. 指令激活（命中路由后）：当路由层判定某个 Skill 被触发时，harness 从 instructionRef 加载指令正文，以独立的System/User消息注入，而不是追加到原始 System Prompt 末尾

这一层需要为单个Skill的加载设置 token 上限，超出时截断并计入日志。避免一个设计不良的Skill把上下文占满

### 三、Skill路由设计

两层级联路由：

1. 规则/关键词层：精确匹配用户显示提及的 Skill 名或强特征词，零延迟返回
2. 轻量LLM兜底层：调用一个轻量模型根据匹配度选出 top-N 个 Skill

**关键设计：Skill路由应该在工具循环开始前确定本轮激活的 Skill 集合，然后只把该 Skill 引用的 ToolCallback 子集传递给下游的 ToolCallingAdvisor**

### 四、Skill 级可观测性

Harness 需要为 Skill 生命周期埋设独立事件，并与记忆模块的 `traceId / turnId` 对齐：

- `SkillRouted`：候选 Skill 列表、最终选择、路由来源（规则/LLM）。
- `SkillActivated`：激活的 Skill ID、指令加载的 token 量、本次激活引用的工具集合。
- `SkillExecuted`：Skill 内工具调用序列、总耗时、成功/失败。
- `SkillDeactivated`：退出原因（任务完成/超时/错误/用户中断）、本次激活的累计 token 消耗。
- `SkillLoadBudgetExceeded`：加载预算超限的具体阶段和 Skill ID。



