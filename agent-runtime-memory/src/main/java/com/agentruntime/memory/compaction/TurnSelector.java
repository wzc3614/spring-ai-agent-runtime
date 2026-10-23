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

import com.agentruntime.api.domain.AgentMessage;
import com.agentruntime.api.enums.MessageRoleEnum;
import com.agentruntime.api.memory.CompactConfig;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 待压缩 Turn 选择器：从候选段中按"锚点强制入选 + 启发式打分 top-K + 最低保护窗口"选出实际送入摘要的 Turn。
 *
 * <p>不引入额外模型，打分纯启发式：
 * <ol>
 *   <li>锚点：候选中首个 USER（初始目标陈述）、含 Decision 关键词的 assistant、含事实关键词的 Turn 直接入选；</li>
 *   <li>剩余打分 = 关键词命中分 + tool 结果加分 + 时间衰减分，按分降序取 top-K；</li>
 *   <li>最近 {@code minProtectWindow} 条候选强制入选。</li>
 * </ol>
 * 最终按 seq 升序返回。候选数不足或无需截断时原样返回。
 */
public class TurnSelector {

    private final CompactConfig config;

    public TurnSelector(CompactConfig config) {
        this.config = config;
    }

    /**
     * 从候选 Turn 中选出实际送入摘要的子集。
     *
     * @param candidates 候选 Turn（已排除最近 keepRecent 条、已过滤 seq<=boundary，按 seq 升序）
     * @return 实际入选 Turn（按 seq 升序）
     */
    public List<AgentMessage> select(List<AgentMessage> candidates) {
        if (CollectionUtils.isEmpty(candidates)) {
            return candidates;
        }
        int total = candidates.size();
        int topK = config.getTopK();
        int protectWindow = Math.min(config.getMinProtectWindow(), total);

        // 候选量不大时无需打分截断：锚点 + 保护窗口已可能覆盖全部
        if (total <= topK + protectWindow) {
            return candidates;
        }

        Set<Integer> selected = new HashSet<>();
        boolean firstUserPicked = false;
        for (int i = 0; i < total; i++) {
            if (isAnchor(candidates.get(i), !firstUserPicked)) {
                selected.add(i);
                if (candidates.get(i).getRole() == MessageRoleEnum.USER) {
                    firstUserPicked = true;
                }
            }
        }

        // 保护窗口：最近 N 条强制入选
        for (int i = total - protectWindow; i < total; i++) {
            selected.add(i);
        }

        // 剩余按分数降序补足到 topK（含已入选锚点/保护窗口）
        List<ScoredIndex> scored = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            scored.add(new ScoredIndex(i, score(candidates.get(i), i, total)));
        }
        scored.sort(Comparator.comparingDouble(ScoredIndex::getScore).reversed());

        for (ScoredIndex si : scored) {
            if (selected.size() >= topK) {
                break;
            }
            selected.add(si.index);
        }

        List<AgentMessage> result = new ArrayList<>(selected.size());
        for (int i = 0; i < total; i++) {
            if (selected.contains(i)) {
                result.add(candidates.get(i));
            }
        }
        return result;
    }

    /**
     * 判定是否为锚点 Turn。
     *
     * @param message        候选消息
     * @param firstUserPending 是否尚未选中首个 USER（用于锚定初始目标陈述）
     * @return true 表示锚点
     */
    private boolean isAnchor(AgentMessage message, boolean firstUserPending) {
        if (firstUserPending && message.getRole() == MessageRoleEnum.USER) {
            return true;
        }
        String text = message.getContent();
        if (text == null) {
            return false;
        }
        if (message.getRole() == MessageRoleEnum.ASSISTANT && containsAny(text, config.getDecisionKeywords())) {
            return true;
        }
        return containsAny(text, config.getAnchorKeywords());
    }

    /**
     * 启发式打分：关键词命中 + tool 结果加分 + 时间衰减。
     *
     * @param message 候选消息
     * @param index   在候选中的位置（0 起，越靠后越新）
     * @param total   候选总数
     * @return 分数（越高越优先保留）
     */
    private double score(AgentMessage message, int index, int total) {
        double score = 0D;
        String text = message.getContent();
        if (text != null) {
            score += countHits(text, config.getAnchorKeywords());
        }
        if (message.getRole() == MessageRoleEnum.TOOL) {
            score += 2D;
        }
        // 时间衰减：越新分越高，归一到 (0,1]
        score += total <= 1 ? 1D : (double) index / (total - 1);
        return score;
    }

    private boolean containsAny(String text, List<String> keywords) {
        if (CollectionUtils.isEmpty(keywords)) {
            return false;
        }
        for (String kw : keywords) {
            if (kw != null && text.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    private int countHits(String text, List<String> keywords) {
        if (CollectionUtils.isEmpty(keywords)) {
            return 0;
        }
        int count = 0;
        for (String kw : keywords) {
            if (kw != null && text.contains(kw)) {
                count++;
            }
        }
        return count;
    }

    /** 带分数的索引，用于排序后取 top-K */
    private static class ScoredIndex {
        private final int index;
        private final double score;

        ScoredIndex(int index, double score) {
            this.index = index;
            this.score = score;
        }

        double getScore() {
            return score;
        }
    }
}
