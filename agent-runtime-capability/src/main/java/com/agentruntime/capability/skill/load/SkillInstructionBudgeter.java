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
package com.agentruntime.capability.skill.load;

import com.agentruntime.api.spi.TokenEstimator;
import com.agentruntime.capability.skill.SkillConstants;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 按 token 预算截断单个 Skill 的指令正文。估算器需对更长文本给出不少于更短前缀的 token 数。
 */
public class SkillInstructionBudgeter {

    private static final Logger logger = LoggerFactory.getLogger(SkillInstructionBudgeter.class);

    private final TokenEstimator tokenEstimator;

    public SkillInstructionBudgeter(TokenEstimator tokenEstimator) {
        this.tokenEstimator = tokenEstimator;
    }

    /**
     * 将正文限制在预算内。
     *
     * @param skillId  Skill 主键，只用于日志
     * @param text     原始正文
     * @param budget   token 上限
     * @return 预算结果
     */
    public SkillInstructionBudget apply(String skillId, String text, int budget) {
        String source = text == null ? "" : text;
        int estimated = tokenEstimator.estimate(source);
        SkillInstructionBudget result = new SkillInstructionBudget();
        result.setEstimatedTokens(estimated);
        if (estimated <= budget) {
            result.setText(source);
            result.setTokens(estimated);
            result.setTruncated(false);
            return result;
        }
        String truncated = truncate(source, budget);
        logger.info("skill instruction truncated, skillId={}, estimatedTokens={}, budget={}",
                skillId, estimated, budget);
        result.setText(truncated);
        result.setTokens(tokenEstimator.estimate(truncated));
        result.setTruncated(true);
        return result;
    }

    private String truncate(String text, int budget) {
        int markerTokens = tokenEstimator.estimate(SkillConstants.TRUNCATION_MARKER);
        if (budget <= markerTokens || StringUtils.isEmpty(text)) {
            return "";
        }
        int contentBudget = budget - markerTokens;
        int best = longestPrefixWithin(text, contentBudget);
        if (best <= 0) {
            return "";
        }
        return text.substring(0, best) + SkillConstants.TRUNCATION_MARKER;
    }

    private int longestPrefixWithin(String text, int contentBudget) {
        int low = 0;
        int high = text.length();
        int best = 0;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int tokens = tokenEstimator.estimate(text.substring(0, mid));
            if (tokens <= contentBudget) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }
}
