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
package com.agentruntime.memory.persistence.jdbc;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * JDBC 通用工具：时间与 JSONB 处理。
 * JSONB 列在 SQL 中用 {@code ?::jsonb} 显式转换，避免耦合 PGobject。
 */
public final class JdbcUtils {

    private JdbcUtils() {
    }

    /**
     * Timestamp 转 Instant。
     *
     * @param ts 数据库时间戳
     * @return Instant，可能为 null
     */
    public static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /**
     * Instant 转 Timestamp。
     *
     * @param instant Instant
     * @return Timestamp，可能为 null
     */
    public static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
