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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 写入任务幂等 ID 生成器：sha256(kind|sessionID|turnID|messageSeq) 的十六进制串。
 */
public final class WriteTaskIdGenerator {

    private WriteTaskIdGenerator() {
    }

    /**
     * 生成幂等任务 ID。
     *
     * @param kind       写入类型（如消息角色）
     * @param sessionId  会话 ID
     * @param turnId     本轮 ID
     * @param messageSeq 消息 seq
     * @return sha256 十六进制串
     */
    public static String generate(String kind, String sessionId, String turnId, long messageSeq) {
        String raw = kind + "|" + sessionId + "|" + turnId + "|" + messageSeq;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return toHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha-256 not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
