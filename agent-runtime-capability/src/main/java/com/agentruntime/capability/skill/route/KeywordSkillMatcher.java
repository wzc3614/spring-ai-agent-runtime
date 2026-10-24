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
package com.agentruntime.capability.skill.route;

import com.agentruntime.api.capability.SkillDescriptor;
import com.agentruntime.capability.skill.SkillConstants;

import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 关键词路由。显式点名 skillId 或 name 优先于触发词；notFor 不参与排除。
 */
public class KeywordSkillMatcher {

    /**
     * 匹配用户输入。
     *
     * @param userText    用户输入
     * @param descriptors 可见 Skill
     * @param topN        最多返回条数
     * @return 命中的 Skill，未命中时为空列表
     */
    public List<SkillDescriptor> match(String userText, List<SkillDescriptor> descriptors, int topN) {
        if (StringUtils.isBlank(userText) || CollectionUtils.isEmpty(descriptors) || topN < 1) {
            return List.of();
        }
        String normalized = userText.toLowerCase(Locale.ROOT);
        List<ScoredSkill> hits = new ArrayList<>();
        for (int i = 0; i < descriptors.size(); i++) {
            SkillDescriptor descriptor = descriptors.get(i);
            int score = score(normalized, descriptor);
            if (score > 0) {
                hits.add(new ScoredSkill(descriptor, score, i));
            }
        }
        hits.sort(Comparator.comparingInt(ScoredSkill::score).reversed().thenComparingInt(ScoredSkill::index));
        List<SkillDescriptor> selected = new ArrayList<>();
        int limit = Math.min(topN, hits.size());
        for (int i = 0; i < limit; i++) {
            selected.add(hits.get(i).descriptor);
        }
        return selected;
    }

    private int score(String normalizedText, SkillDescriptor descriptor) {
        int best = 0;
        if (containsName(normalizedText, descriptor.getSkillId())) {
            best = Math.max(best, explicitScore(descriptor.getSkillId()));
        }
        if (containsName(normalizedText, descriptor.getName())) {
            best = Math.max(best, explicitScore(descriptor.getName()));
        }
        if (CollectionUtils.isEmpty(descriptor.getTriggers())) {
            return best;
        }
        for (String trigger : descriptor.getTriggers()) {
            if (StringUtils.length(trigger) < SkillConstants.MIN_TRIGGER_LENGTH) {
                continue;
            }
            String needle = trigger.toLowerCase(Locale.ROOT);
            if (normalizedText.contains(needle)) {
                best = Math.max(best, trigger.length());
            }
        }
        return best;
    }

    private int explicitScore(String name) {
        return SkillConstants.EXPLICIT_NAME_SCORE_BASE + StringUtils.length(name);
    }

    private boolean containsName(String normalizedText, String name) {
        if (StringUtils.isBlank(name)) {
            return false;
        }
        String needle = name.toLowerCase(Locale.ROOT);
        if (isAscii(needle)) {
            return Pattern.compile("\\b" + Pattern.quote(needle) + "\\b").matcher(normalizedText).find();
        }
        return normalizedText.contains(needle);
    }

    private boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static final class ScoredSkill {

        private final SkillDescriptor descriptor;

        private final int score;

        private final int index;

        private ScoredSkill(SkillDescriptor descriptor, int score, int index) {
            this.descriptor = descriptor;
            this.score = score;
            this.index = index;
        }

        private int score() {
            return score;
        }

        private int index() {
            return index;
        }
    }
}
