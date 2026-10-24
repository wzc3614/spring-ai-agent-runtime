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

import java.util.List;

/**
 * Skill 路由器。在工具循环开始前确定本轮激活的 Skill 集合。
 */
public interface SkillRouter {

    /**
     * 为当前用户输入选择 Skill。
     *
     * @param userText    用户输入
     * @param descriptors 当前可见 Skill
     * @return 路由结果
     */
    SkillRouteResult route(String userText, List<SkillDescriptor> descriptors);
}
