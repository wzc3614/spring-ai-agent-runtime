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

/**
 * 按 {@code instructionRef} 加载 Skill 指令正文。每次激活都重新读取，不做缓存。
 */
public interface SkillInstructionLoader {

    /**
     * 加载指令正文。
     *
     * @param instructionRef 正文地址
     * @return 正文；地址无法解析时返回空串
     */
    String load(String instructionRef);
}
