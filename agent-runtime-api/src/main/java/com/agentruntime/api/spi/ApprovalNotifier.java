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
package com.agentruntime.api.spi;

import com.agentruntime.api.domain.ApprovalRecord;

/**
 * 审批通知 SPI。V1 仅提供空实现与日志实现，留给使用者自行接入企业微信/飞书/Slack/邮件等渠道。
 */
public interface ApprovalNotifier {

    /**
     * 通知有新的待审批请求。
     *
     * @param approval 审批记录
     */
    void notifyPending(ApprovalRecord approval);

    /**
     * 通知审批已出结果。
     *
     * @param approval 审批记录
     */
    void notifyDecided(ApprovalRecord approval);
}
