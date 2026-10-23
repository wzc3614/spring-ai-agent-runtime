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
package com.agentruntime.api.domain;

import java.time.Instant;
import java.util.List;

/**
 * 摘要内容通用容器，对应 agent_summary_snapshot.sections JSONB。
 *
 * <p>结构化版本（schemaVersion=2）对齐 Hermes 模板，按信息类型分层：
 * Goal / Constraints & Preferences / Progress(Done/In Progress/Blocked) /
 * Key Decisions / Facts / Open Questions / Next Steps / Provenance。
 *
 * <p>向后兼容：schemaVersion=1 或结构化字段全空时，仅 {@link #summary} 一段式文本有效，
 * 渲染层据此回退。memory 层负责本对象与 JSON 字符串互转。
 */
public class SummaryContent {

    /** 结构版本号：1=一段式（兼容），2=结构化分层 */
    private int schemaVersion = 2;

    /** 一段式自然语言摘要（兼容字段；结构化字段全空时作为回退渲染） */
    private String summary;

    /** 用户核心目标的当前表述（可能随对话演变更新） */
    private String goal;

    /** 硬性约束 */
    private List<String> constraints;

    /** 用户偏好与系统设定 */
    private List<String> preferences;

    /** 已完成项 */
    private List<String> progressDone;

    /** 进行中项 */
    private List<String> progressInProgress;

    /** 阻塞项 */
    private List<String> progressBlocked;

    /** 重大决策及理由 */
    private List<Decision> keyDecisions;

    /** 精确事实 */
    private List<String> facts;

    /** 待处理项 / 开放问题 */
    private List<String> openQuestions;

    /** 下一步 */
    private List<String> nextSteps;

    /** 本次摘要覆盖范围，用于回退与可观测 */
    private Provenance provenance;

    public int getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(int schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getGoal() {
        return goal;
    }

    public void setGoal(String goal) {
        this.goal = goal;
    }

    public List<String> getConstraints() {
        return constraints;
    }

    public void setConstraints(List<String> constraints) {
        this.constraints = constraints;
    }

    public List<String> getPreferences() {
        return preferences;
    }

    public void setPreferences(List<String> preferences) {
        this.preferences = preferences;
    }

    public List<String> getProgressDone() {
        return progressDone;
    }

    public void setProgressDone(List<String> progressDone) {
        this.progressDone = progressDone;
    }

    public List<String> getProgressInProgress() {
        return progressInProgress;
    }

    public void setProgressInProgress(List<String> progressInProgress) {
        this.progressInProgress = progressInProgress;
    }

    public List<String> getProgressBlocked() {
        return progressBlocked;
    }

    public void setProgressBlocked(List<String> progressBlocked) {
        this.progressBlocked = progressBlocked;
    }

    public List<Decision> getKeyDecisions() {
        return keyDecisions;
    }

    public void setKeyDecisions(List<Decision> keyDecisions) {
        this.keyDecisions = keyDecisions;
    }

    public List<String> getFacts() {
        return facts;
    }

    public void setFacts(List<String> facts) {
        this.facts = facts;
    }

    public List<String> getOpenQuestions() {
        return openQuestions;
    }

    public void setOpenQuestions(List<String> openQuestions) {
        this.openQuestions = openQuestions;
    }

    public List<String> getNextSteps() {
        return nextSteps;
    }

    public void setNextSteps(List<String> nextSteps) {
        this.nextSteps = nextSteps;
    }

    public Provenance getProvenance() {
        return provenance;
    }

    public void setProvenance(Provenance provenance) {
        this.provenance = provenance;
    }

    /**
     * 判断结构化字段是否全部为空（用于渲染回退到一段式 summary）。
     *
     * @return true 表示无任何结构化内容
     */
    public boolean isStructuredEmpty() {
        return goal == null
                && isEmpty(constraints)
                && isEmpty(preferences)
                && isEmpty(progressDone)
                && isEmpty(progressInProgress)
                && isEmpty(progressBlocked)
                && isEmpty(keyDecisions)
                && isEmpty(facts)
                && isEmpty(openQuestions)
                && isEmpty(nextSteps);
    }

    private static boolean isEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    /**
     * 重大决策：做了什么 + 为什么。
     */
    public static class Decision {

        /** 决策内容 */
        private String what;

        /** 决策理由 */
        private String why;

        public String getWhat() {
            return what;
        }

        public void setWhat(String what) {
            this.what = what;
        }

        public String getWhy() {
            return why;
        }

        public void setWhy(String why) {
            this.why = why;
        }
    }

    /**
     * 摘要覆盖范围与产出元数据，用于回退、可观测与增量更新定位。
     */
    public static class Provenance {

        /** 本次摘要覆盖到的最大 message seq（不含） */
        private long summarizedUpToSeq;

        /** 本次覆盖的 message id 列表 */
        private List<String> coveredMessageIds;

        /** 本次压缩发生时间 */
        private Instant compactedAt;

        /** 产出本次摘要的模型标识 */
        private String modelTag;

        /** 本次压缩状态（SUCCESS/PARTIAL/FAILED） */
        private String status;

        /** 失败原因（status=FAILED 时填充） */
        private String errorMessage;

        public long getSummarizedUpToSeq() {
            return summarizedUpToSeq;
        }

        public void setSummarizedUpToSeq(long summarizedUpToSeq) {
            this.summarizedUpToSeq = summarizedUpToSeq;
        }

        public List<String> getCoveredMessageIds() {
            return coveredMessageIds;
        }

        public void setCoveredMessageIds(List<String> coveredMessageIds) {
            this.coveredMessageIds = coveredMessageIds;
        }

        public Instant getCompactedAt() {
            return compactedAt;
        }

        public void setCompactedAt(Instant compactedAt) {
            this.compactedAt = compactedAt;
        }

        public String getModelTag() {
            return modelTag;
        }

        public void setModelTag(String modelTag) {
            this.modelTag = modelTag;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public void setErrorMessage(String errorMessage) {
            this.errorMessage = errorMessage;
        }
    }
}
