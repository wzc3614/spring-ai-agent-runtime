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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 搜索类工具结果的确定性摘要。不调用模型。
 * 数组保留条数和装得下的前若干条，对象截断过长的字符串值，其余按字符截断。
 */
public class SearchResultSummarizer {

    private static final Logger log = LoggerFactory.getLogger(SearchResultSummarizer.class);

    private static final String FIELD_COUNT = "count";

    private static final String FIELD_PREVIEW = "preview";

    private final ObjectMapper objectMapper;

    public SearchResultSummarizer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param raw      原文
     * @param maxChars 摘要字符上限
     * @return 不超过上限的摘要；原文更短时原样返回
     */
    public String summarize(String raw, int maxChars) {
        if (!StringUtils.hasText(raw) || maxChars <= 0) {
            return "";
        }
        if (raw.length() <= maxChars) {
            return raw;
        }
        JsonNode node = tryParse(raw);
        if (node == null || node.isNull()) {
            return truncate(raw, maxChars);
        }
        if (node.isArray()) {
            return summarizeArray(node, maxChars);
        }
        if (node.isObject()) {
            return summarizeObject((ObjectNode) node, maxChars);
        }
        return truncate(raw, maxChars);
    }

    private String summarizeArray(JsonNode node, int maxChars) {
        ArrayNode preview = objectMapper.createArrayNode();
        for (JsonNode item : node) {
            ArrayNode candidate = preview.deepCopy();
            candidate.add(item);
            if (previewJson(node.size(), candidate).length() > maxChars) {
                break;
            }
            preview = candidate;
        }
        String encoded = previewJson(node.size(), preview);
        if (encoded.length() <= maxChars) {
            return encoded;
        }
        return truncate(encoded, maxChars);
    }

    private String summarizeObject(ObjectNode node, int maxChars) {
        ObjectNode copy = node.deepCopy();
        List<String> names = new ArrayList<>();
        copy.fieldNames().forEachRemaining(names::add);
        for (String name : names) {
            JsonNode value = copy.get(name);
            if (value != null && value.isTextual() && value.asText().length() > maxChars) {
                copy.put(name, truncate(value.asText(), maxChars));
            }
        }
        String encoded = write(copy);
        if (encoded.length() <= maxChars) {
            return encoded;
        }
        return truncate(encoded, maxChars);
    }

    private String previewJson(int count, ArrayNode preview) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put(FIELD_COUNT, count);
        root.set(FIELD_PREVIEW, preview);
        return write(root);
    }

    private JsonNode tryParse(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.warn("encode search summary failed");
            return "";
        }
    }

    private String truncate(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text == null ? "" : text;
        }
        return text.substring(0, maxChars);
    }
}
