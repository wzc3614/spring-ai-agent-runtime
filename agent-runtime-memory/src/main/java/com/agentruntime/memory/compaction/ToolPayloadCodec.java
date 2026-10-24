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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具原文 JSON 的读写。key 是工具调用 id。
 */
public class ToolPayloadCodec {

    private static final Logger log = LoggerFactory.getLogger(ToolPayloadCodec.class);

    private static final TypeReference<LinkedHashMap<String, ToolPayloadEntry>> MAP_TYPE =
            new TypeReference<LinkedHashMap<String, ToolPayloadEntry>>() {
            };

    private final ObjectMapper objectMapper;

    public ToolPayloadCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param json payload 列，空则返回空 map
     * @return 按插入顺序的原文，解析失败时返回空 map
     */
    public Map<String, ToolPayloadEntry> read(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, ToolPayloadEntry> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (JsonProcessingException e) {
            log.warn("parse tool payload failed");
            return new LinkedHashMap<>();
        }
    }

    /**
     * @param entries 原文
     * @return JSON，空 map 返回 null
     */
    public String write(Map<String, ToolPayloadEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(entries);
        } catch (JsonProcessingException e) {
            log.warn("encode tool payload failed");
            return null;
        }
    }
}
