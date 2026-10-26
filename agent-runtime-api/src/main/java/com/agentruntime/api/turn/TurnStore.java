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
package com.agentruntime.api.turn;

import com.agentruntime.api.domain.TurnBeginResult;
import com.agentruntime.api.domain.TurnFrame;
import com.agentruntime.api.domain.TurnRecord;
import com.agentruntime.api.enums.TurnFrameTypeEnum;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 轮次执行存储。权威历史不在这里，这里只放状态、租约和回放帧。
 */
public interface TurnStore {

    /**
     * 占住会话并创建 ACCEPTED 轮次。已有未完成轮时复用，不覆盖 query。
     *
     * @param draft 草稿，至少要有 turnId、sessionId、query、agentName
     * @return 新建或复用的轮次
     */
    TurnBeginResult begin(TurnRecord draft);

    /**
     * 把 ACCEPTED 标成 RUNNING 并写入租约。已经是同一持有者时只刷新截止时间。
     *
     * @param turnId        轮次
     * @param leaseOwner    持有者
     * @param leaseDeadline 截止时间
     */
    void markRunning(String turnId, String leaseOwner, Instant leaseDeadline);

    /**
     * 仅当持有者匹配且仍在 RUNNING 时续约。
     *
     * @param turnId        轮次
     * @param leaseOwner    持有者
     * @param leaseDeadline 新的截止时间
     * @return 是否续上
     */
    boolean renewLease(String turnId, String leaseOwner, Instant leaseDeadline);

    /**
     * 认领过期轮次。成功后状态为 RUNNING，旧事件被清掉，只留一条 TURN_RESTARTED。
     *
     * @param turnId         轮次
     * @param leaseOwner     新持有者
     * @param leaseDeadline  新租约
     * @param acceptedBefore 早于该时间的 ACCEPTED 视为卡住
     * @return 是否认领成功
     */
    boolean tryClaimExpired(String turnId, String leaseOwner, Instant leaseDeadline, Instant acceptedBefore);

    /**
     * 追加一帧。持有者不匹配或轮次已结束时返回 -1，调用方应停止执行。
     *
     * @param turnId     轮次
     * @param leaseOwner 持有者
     * @param type       帧类型
     * @param text       文本
     * @return 分配的 seq，失败时为 -1
     */
    long appendEvent(String turnId, String leaseOwner, TurnFrameTypeEnum type, String text);

    /**
     * 持有者匹配时完成轮次，并写入 DONE。
     *
     * @param turnId     轮次
     * @param leaseOwner 持有者
     * @param answer     回答全文
     * @return 是否写上终态
     */
    boolean complete(String turnId, String leaseOwner, String answer);

    /**
     * 持有者匹配时失败，并写入 ERROR。
     *
     * @param turnId     轮次
     * @param leaseOwner 持有者
     * @param errorCode  错误码
     * @return 是否写上终态
     */
    boolean fail(String turnId, String leaseOwner, String errorCode);

    /**
     * 租约已过期时失败关闭，并写入 ERROR。用于非幂等工具已经开始的轮次。
     *
     * @param turnId         轮次
     * @param errorCode      错误码
     * @param acceptedBefore 卡住的 ACCEPTED 阈值
     * @return 是否写上终态
     */
    boolean failExpired(String turnId, String errorCode, Instant acceptedBefore);

    /**
     * 持有者匹配时取消，并写入 ERROR。
     *
     * @param turnId     轮次
     * @param leaseOwner 持有者
     * @param errorCode  错误码
     * @return 是否写上终态
     */
    boolean cancel(String turnId, String leaseOwner, String errorCode);

    /**
     * 读取轮次。实现应返回副本或不可变视图，避免调用方改到存储内部。
     *
     * @param turnId 轮次
     * @return 轮次
     */
    Optional<TurnRecord> find(String turnId);

    /**
     * 本 agent 下可以认领的轮次。
     *
     * @param agentName      agent 名称
     * @param now            当前时间
     * @param acceptedBefore 卡住的 ACCEPTED 阈值
     * @return 可恢复轮次
     */
    List<TurnRecord> listRecoverable(String agentName, Instant now, Instant acceptedBefore);

    /**
     * 读取 seq 大于 afterSeq 的帧，按 seq 升序。
     *
     * @param turnId   轮次
     * @param afterSeq 游标，不含
     * @return 帧列表
     */
    List<TurnFrame> listEvents(String turnId, long afterSeq);

    /**
     * 记下本轮有非幂等工具已经开始。
     *
     * @param turnId 轮次
     */
    void markUnsafeTool(String turnId);

    /**
     * @param turnId 轮次
     * @return 是否已有非幂等工具开始执行
     */
    boolean hasUnsafeTool(String turnId);
}
