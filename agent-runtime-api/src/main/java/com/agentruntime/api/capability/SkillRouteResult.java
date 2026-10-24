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

import com.agentruntime.api.enums.SkillRouteSourceEnum;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮 Skill 路由的结果。
 */
public class SkillRouteResult {

    /** 本轮选中的 Skill，顺序即指令注入顺序 */
    private List<SkillDescriptor> selected = new ArrayList<>();

    /** 参与路由的候选 skillId */
    private List<String> candidates = new ArrayList<>();

    /** 路由来源 */
    private SkillRouteSourceEnum source = SkillRouteSourceEnum.NONE;

    public List<SkillDescriptor> getSelected() {
        return selected;
    }

    public void setSelected(List<SkillDescriptor> selected) {
        this.selected = selected;
    }

    public List<String> getCandidates() {
        return candidates;
    }

    public void setCandidates(List<String> candidates) {
        this.candidates = candidates;
    }

    public SkillRouteSourceEnum getSource() {
        return source;
    }

    public void setSource(SkillRouteSourceEnum source) {
        this.source = source;
    }
}
