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
package com.agentruntime.memory.compaction;

import com.agentruntime.api.enums.CompactionFailureKindEnum;

/**
 * 压缩管道可识别异常，携带 {@link CompactionFailureKindEnum} 供降级策略分流。
 */
public class CompactionException extends RuntimeException {

    private final CompactionFailureKindEnum kind;

    public CompactionException(CompactionFailureKindEnum kind, String message) {
        super(message);
        this.kind = kind;
    }

    public CompactionException(CompactionFailureKindEnum kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public CompactionFailureKindEnum getKind() {
        return kind;
    }
}
