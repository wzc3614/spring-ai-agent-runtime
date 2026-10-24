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
package com.agentruntime.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Skill 管道配置，绑定 {@code agent.runtime.skill.*}。
 * 注册表为空时编排器保持原行为。
 */
@ConfigurationProperties(prefix = "agent.runtime.skill")
public class SkillProperties {

    /** 路由模型 bean 名。没有该 bean 时只走关键词路由 */
    public static final String ROUTER_MODEL_BEAN = "skillRouterChatModel";

    /** 总开关。false 时不装配 SkillRuntime */
    private boolean enabled = true;

    /** 单个 Skill 指令 token 上限 */
    private int instructionMaxTokens = 2000;

    private Router router = new Router();

    private Sources sources = new Sources();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getInstructionMaxTokens() {
        return instructionMaxTokens;
    }

    public void setInstructionMaxTokens(int instructionMaxTokens) {
        this.instructionMaxTokens = instructionMaxTokens;
    }

    public Router getRouter() {
        return router;
    }

    public void setRouter(Router router) {
        this.router = router;
    }

    public Sources getSources() {
        return sources;
    }

    public void setSources(Sources sources) {
        this.sources = sources;
    }

    /** 路由配置 */
    public static class Router {

        /** 最多激活的 Skill 数 */
        private int topN = 1;

        /** 关键词未命中时是否调用轻量模型 */
        private boolean llmEnabled = true;

        public int getTopN() {
            return topN;
        }

        public void setTopN(int topN) {
            this.topN = topN;
        }

        public boolean isLlmEnabled() {
            return llmEnabled;
        }

        public void setLlmEnabled(boolean llmEnabled) {
            this.llmEnabled = llmEnabled;
        }
    }

    /** 元数据来源 */
    public static class Sources {

        private Classpath classpath = new Classpath();

        private Jdbc jdbc = new Jdbc();

        public Classpath getClasspath() {
            return classpath;
        }

        public void setClasspath(Classpath classpath) {
            this.classpath = classpath;
        }

        public Jdbc getJdbc() {
            return jdbc;
        }

        public void setJdbc(Jdbc jdbc) {
            this.jdbc = jdbc;
        }
    }

    /** classpath 上的 skill.json */
    public static class Classpath {

        private boolean enabled = true;

        private String location = "classpath*:skills/*/skill.json";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }
    }

    /** agent_skill 表 */
    public static class Jdbc {

        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
