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
package com.agentruntime.memory.write;

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.memory.MessageWritePolicy;

/**
 * 默认写入策略：写入全部消息。使用者可提供自定义实现覆盖。
 */
public class AlwaysWritePolicy implements MessageWritePolicy {

    @Override
    public boolean shouldWrite(String sessionId, String turnId, AgentMessage message) {
        return true;
    }
}
