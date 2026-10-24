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
 * Skill 元数据来源。classpath、数据库以及后续的 Git / 配置中心都实现此接口。
 * {@link #revision()} 只反映元数据，不读取指令正文。
 */
public interface SkillSource {

    /**
     * 来源名称，用于 revision 拼接和日志。
     *
     * @return 来源名
     */
    String name();

    /**
     * 元数据 revision。未变化时与上次相等。
     *
     * @return revision 令牌
     */
    String revision();

    /**
     * 加载元数据。不包含指令正文。
     *
     * @return Skill 描述列表
     */
    List<SkillDescriptor> loadMetadata();
}
