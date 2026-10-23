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

import com.agentruntime.api.domain.SummaryContent;
import org.springframework.util.CollectionUtils;

import java.util.List;

/**
 * 第四层结构化摘要校验：产出必须有可用内容，决策项必须带 what。
 * 校验失败时由调用方标记 FAILED，不覆盖旧摘要。
 */
public class SummaryContentValidator {

    /**
     * 校验结构化摘要是否可持久化为可用快照。
     *
     * @param content 摘要内容
     * @return true 表示通过
     */
    public boolean isValid(SummaryContent content) {
        if (content == null) {
            return false;
        }
        if (content.isStructuredEmpty() && isBlank(content.getSummary())) {
            return false;
        }
        return decisionsValid(content.getKeyDecisions());
    }

    private boolean decisionsValid(List<SummaryContent.Decision> decisions) {
        if (CollectionUtils.isEmpty(decisions)) {
            return true;
        }
        for (SummaryContent.Decision decision : decisions) {
            if (decision == null || isBlank(decision.getWhat())) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
