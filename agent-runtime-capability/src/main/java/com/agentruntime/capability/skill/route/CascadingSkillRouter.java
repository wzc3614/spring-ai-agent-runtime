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
import com.agentruntime.api.capability.SkillRouteResult;
import com.agentruntime.api.capability.SkillRouter;
import com.agentruntime.api.enums.SkillRouteSourceEnum;
import com.agentruntime.capability.skill.SkillConstants;

import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 两级路由：关键词命中立即返回；否则交给轻量模型。模型不存在时保持空选择。
 */
public class CascadingSkillRouter implements SkillRouter {

    private final KeywordSkillMatcher keywordSkillMatcher;

    private final LlmSkillRouter llmSkillRouter;

    private final int topN;

    public CascadingSkillRouter(KeywordSkillMatcher keywordSkillMatcher, LlmSkillRouter llmSkillRouter, int topN) {
        this.keywordSkillMatcher = keywordSkillMatcher;
        this.llmSkillRouter = llmSkillRouter;
        this.topN = topN < 1 ? SkillConstants.DEFAULT_TOP_N : topN;
    }

    @Override
    public SkillRouteResult route(String userText, List<SkillDescriptor> descriptors) {
        List<SkillDescriptor> keywordHits = keywordSkillMatcher.match(userText, descriptors, topN);
        if (!CollectionUtils.isEmpty(keywordHits)) {
            SkillRouteResult result = new SkillRouteResult();
            result.setSelected(keywordHits);
            result.setCandidates(ids(descriptors));
            result.setSource(SkillRouteSourceEnum.RULE);
            return result;
        }
        if (llmSkillRouter == null) {
            SkillRouteResult result = new SkillRouteResult();
            result.setSelected(List.of());
            result.setCandidates(ids(descriptors));
            result.setSource(SkillRouteSourceEnum.NONE);
            return result;
        }
        return llmSkillRouter.route(userText, descriptors);
    }

    private List<String> ids(List<SkillDescriptor> descriptors) {
        List<String> ids = new ArrayList<>();
        if (CollectionUtils.isEmpty(descriptors)) {
            return ids;
        }
        for (SkillDescriptor descriptor : descriptors) {
            ids.add(descriptor.getSkillId());
        }
        return ids;
    }
}
