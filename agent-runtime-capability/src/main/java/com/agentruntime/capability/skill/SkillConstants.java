/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentruntime.capability.skill;

/**
 * Skill 管道使用的字面量。业务含义都收在这里，调用处不出现裸字符串。
 */
public final class SkillConstants {

    /** classpath 指令地址前缀 */
    public static final String SCHEME_CLASSPATH = "classpath:";

    /** 数据库指令地址前缀 */
    public static final String SCHEME_DB = "db:";

    /** 注入系统提示的目录标题 */
    public static final String CATALOG_HEADER = "# Skills";

    /** 目录中的负例标签 */
    public static final String NOT_FOR_LABEL = "not-for: ";

    /** 指令被截断时追加的标记 */
    public static final String TRUNCATION_MARKER = "\n[skill instruction truncated]";

    /** 加载预算超限的阶段：指令正文 */
    public static final String STAGE_INSTRUCTION = "INSTRUCTION";

    /** 默认路由 top-N */
    public static final int DEFAULT_TOP_N = 1;

    /** 单个 Skill 指令 token 上限的默认值 */
    public static final int DEFAULT_INSTRUCTION_MAX_TOKENS = 2000;

    /** 触发词最短长度，短于该值的词不参与关键词匹配 */
    public static final int MIN_TRIGGER_LENGTH = 2;

    /** skillId 合法性 */
    public static final String SKILL_ID_PATTERN = "^[a-z0-9]+(?:-[a-z0-9]+)*$";

    /** 显式点名的排序基数，保证高于任意触发词长度 */
    public static final int EXPLICIT_NAME_SCORE_BASE = 1_000_000;

    /** 元数据哈希算法 */
    public static final String HASH_ALGORITHM = "SHA-256";

    /** classpath 来源名 */
    public static final String SOURCE_CLASSPATH = "classpath";

    /** 数据库来源名 */
    public static final String SOURCE_JDBC = "jdbc";

    /** 事件载荷：候选 skillId */
    public static final String PAYLOAD_CANDIDATES = "candidates";

    /** 事件载荷：选中 skillId */
    public static final String PAYLOAD_SELECTED = "selected";

    /** 事件载荷：路由来源 */
    public static final String PAYLOAD_SOURCE = "source";

    /** 事件载荷：激活的 skillId */
    public static final String PAYLOAD_SKILL_IDS = "skillIds";

    /** 事件载荷：指令 token */
    public static final String PAYLOAD_INSTRUCTION_TOKENS = "instructionTokens";

    /** 事件载荷：本轮放行的 Skill 工具名 */
    public static final String PAYLOAD_TOOL_NAMES = "toolNames";

    /** 事件载荷：实际工具调用顺序 */
    public static final String PAYLOAD_TOOL_SEQUENCE = "toolSequence";

    /** 事件载荷：主调用耗时 */
    public static final String PAYLOAD_DURATION_MS = "durationMs";

    /** 事件载荷：本轮是否成功 */
    public static final String PAYLOAD_SUCCESS = "success";

    /** 事件载荷：退出原因 */
    public static final String PAYLOAD_REASON = "reason";

    /** 事件载荷：本次激活估算 token */
    public static final String PAYLOAD_ACTIVATION_TOKENS = "activationTokens";

    /** 事件载荷：单个 skillId */
    public static final String PAYLOAD_SKILL_ID = "skillId";

    /** 事件载荷：超限阶段 */
    public static final String PAYLOAD_STAGE = "stage";

    /** 事件载荷：截断前估算 token */
    public static final String PAYLOAD_ESTIMATED_TOKENS = "estimatedTokens";

    /** 事件载荷：预算 */
    public static final String PAYLOAD_BUDGET = "budget";

    /** SKILL span 属性：skillId 列表 */
    public static final String ATTR_SKILL_IDS = "skillIds";

    /** SKILL span 属性：路由来源 */
    public static final String ATTR_ROUTE_SOURCE = "routeSource";

    /** 路由模型提示词。提示词允许中文。 */
    public static final String ROUTER_PROMPT = """
            你是 Skill 路由器。根据用户输入从候选 Skill 中选出最相关的至多 %d 个。
            只输出 JSON，不要输出其他文字：{"skillIds":["..."],"reason":"..."}
            如果都不相关，skillIds 为空数组。notFor 命中时不要选择该 Skill。
            候选：
            %s
            用户输入：
            %s
            """;

    private SkillConstants() {
    }
}
