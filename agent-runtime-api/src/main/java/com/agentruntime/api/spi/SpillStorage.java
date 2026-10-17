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
package com.agentruntime.api.spi;

import java.io.InputStream;

/**
 * Spill 对象存储 SPI，默认实现基于 MinIO/S3 协议。
 * 注意：对象存储不参与 DB 事务，实现需在 DB 回滚时补偿删除已写入的孤儿对象。
 */
public interface SpillStorage {

    /**
     * 存储对象。
     *
     * @param namespace 命名空间（agentName/sessionId 维度）
     * @param key       对象 key
     * @param content   内容流
     * @param size       内容字节数
     * @return 完整对象 key
     */
    String store(String namespace, String key, InputStream content, long size);

    /**
     * 读取完整对象。
     *
     * @param objectKey 对象 key
     * @return 内容流
     */
    InputStream read(String objectKey);

    /**
     * 按字节区间读取片段。
     *
     * @param objectKey 对象 key
     * @param start     起始字节（含）
     * @param end       结束字节（含）
     * @return 片段文本
     */
    String readRange(String objectKey, long start, long end);

    /**
     * 按 JSONPath 读取片段。
     *
     * @param objectKey 对象 key
     * @param jsonPath  JSONPath 表达式
     * @return 片段文本
     */
    String readJsonPath(String objectKey, String jsonPath);

    /**
     * 按分页游标读取片段。
     *
     * @param objectKey 对象 key
     * @param offset    偏移量
     * @param limit     每页大小
     * @return 片段文本
     */
    String readPage(String objectKey, int offset, int limit);

    /**
     * 删除单个对象。
     *
     * @param objectKey 对象 key
     */
    void delete(String objectKey);

    /**
     * 按命名空间批量删除（会话结束清理用）。
     *
     * @param namespace 命名空间
     * @return 删除数量
     */
    int deleteByNamespace(String namespace);
}
