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
package com.agentruntime.api.capability;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 的可移植契约。只描述元数据，指令正文通过 {@link #instructionRef} 惰性加载，不进入本对象。
 */
public class SkillDescriptor {

    /** 稳定主键，小写字母、数字和连字符 */
    private String skillId;

    /** 展示名，参与目录与关键词匹配 */
    private String name;

    /** 何时使用，路由与目录的正向描述 */
    private String description;

    /** 何时不要使用，只进入目录和路由模型 */
    private List<String> notFor = new ArrayList<>();

    /** 强特征词，关键词路由使用 */
    private List<String> triggers = new ArrayList<>();

    /** 引用的 ToolCallback 名称 */
    private List<String> toolRefs = new ArrayList<>();

    /** 指令正文地址，如 classpath: 或 db: */
    private String instructionRef;

    /** 业务版本 */
    private String version;

    /** 元数据内容哈希，不含指令正文 */
    private String contentHash;

    /** 是否参与目录与路由。false 仍占据 skillId，用于下线同名 Skill */
    private boolean enabled = true;

    /** owner、风险等级等扩展信息 */
    private Map<String, String> meta = new LinkedHashMap<>();

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getNotFor() {
        return notFor;
    }

    public void setNotFor(List<String> notFor) {
        this.notFor = notFor;
    }

    public List<String> getTriggers() {
        return triggers;
    }

    public void setTriggers(List<String> triggers) {
        this.triggers = triggers;
    }

    public List<String> getToolRefs() {
        return toolRefs;
    }

    public void setToolRefs(List<String> toolRefs) {
        this.toolRefs = toolRefs;
    }

    public String getInstructionRef() {
        return instructionRef;
    }

    public void setInstructionRef(String instructionRef) {
        this.instructionRef = instructionRef;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, String> getMeta() {
        return meta;
    }

    public void setMeta(Map<String, String> meta) {
        this.meta = meta;
    }
}
