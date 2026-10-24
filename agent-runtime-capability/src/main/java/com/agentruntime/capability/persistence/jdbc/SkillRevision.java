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
package com.agentruntime.capability.persistence.jdbc;

/**
 * agent_skill 的 revision 行。只含比对所需列，不含指令正文。
 */
public class SkillRevision {

    private String skillId;

    private String contentHash;

    private long updatedAtEpochMilli;

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public long getUpdatedAtEpochMilli() {
        return updatedAtEpochMilli;
    }

    public void setUpdatedAtEpochMilli(long updatedAtEpochMilli) {
        this.updatedAtEpochMilli = updatedAtEpochMilli;
    }
}
