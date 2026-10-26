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
package com.agentruntime.memory.turn;

import com.agentruntime.api.domain.TurnBeginResult;
import com.agentruntime.api.domain.TurnFrame;
import com.agentruntime.api.domain.TurnRecord;
import com.agentruntime.api.enums.TurnFrameTypeEnum;
import com.agentruntime.api.enums.TurnStatusEnum;
import com.agentruntime.api.turn.TurnStore;
import com.agentruntime.memory.persistence.jdbc.JdbcTurnRepository;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Redis 上的轮次状态、租约和回放流。终态同时镜像到 {@code agent_turn}。
 * 脚本按单机 Redis 编写，会访问由前缀拼出来的键。
 */
public class RedisTurnStore implements TurnStore {

    private static final Logger log = LoggerFactory.getLogger(RedisTurnStore.class);

    private static final String CREATED_PREFIX = "CREATED:";

    private static final String REUSED_PREFIX = "REUSED:";

    private static final DefaultRedisScript<String> BEGIN_SCRIPT = new DefaultRedisScript<>("""
            local active = redis.call('GET', KEYS[1])
            if active and active ~= '' then
              local status = redis.call('HGET', ARGV[7] .. active, 'status')
              if status == 'ACCEPTED' or status == 'RUNNING' then
                return 'REUSED:' .. active
              end
            end
            redis.call('HSET', KEYS[2],
              'turnId', ARGV[2], 'sessionId', ARGV[1], 'userId', ARGV[3], 'agentName', ARGV[4],
              'query', ARGV[5], 'status', 'ACCEPTED', 'leaseOwner', '', 'leaseDeadline', '',
              'errorCode', '', 'finalAnswer', '', 'createdAt', ARGV[6], 'updatedAt', ARGV[6], 'nextSeq', '0')
            redis.call('SET', KEYS[1], ARGV[2])
            redis.call('SADD', KEYS[3], ARGV[2])
            return 'CREATED:' .. ARGV[2]
            """, String.class);

    private static final DefaultRedisScript<Long> MARK_RUNNING_SCRIPT = new DefaultRedisScript<>("""
            local status = redis.call('HGET', KEYS[1], 'status')
            if status ~= 'ACCEPTED' and status ~= 'RUNNING' then return 0 end
            if status == 'RUNNING' and redis.call('HGET', KEYS[1], 'leaseOwner') ~= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[1], 'status', 'RUNNING', 'leaseOwner', ARGV[1],
              'leaseDeadline', ARGV[2], 'updatedAt', ARGV[3])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> RENEW_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'status') ~= 'RUNNING' then return 0 end
            if redis.call('HGET', KEYS[1], 'leaseOwner') ~= ARGV[1] then return 0 end
            redis.call('HSET', KEYS[1], 'leaseDeadline', ARGV[2], 'updatedAt', ARGV[3])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> CLAIM_SCRIPT = new DefaultRedisScript<>("""
            local status = redis.call('HGET', KEYS[1], 'status')
            local now = tonumber(ARGV[3])
            local acceptedBefore = tonumber(ARGV[4])
            local deadline = redis.call('HGET', KEYS[1], 'leaseDeadline')
            local createdAt = tonumber(redis.call('HGET', KEYS[1], 'createdAt'))
            local expired = false
            if status == 'RUNNING' then
              if (not deadline) or deadline == '' or tonumber(deadline) <= now then expired = true end
            elseif status == 'ACCEPTED' then
              if createdAt and createdAt <= acceptedBefore then expired = true end
            end
            if not expired then return 0 end
            redis.call('DEL', KEYS[2])
            redis.call('HSET', KEYS[1], 'status', 'RUNNING', 'leaseOwner', ARGV[1],
              'leaseDeadline', ARGV[2], 'updatedAt', ARGV[3], 'nextSeq', '0')
            local seq = redis.call('HINCRBY', KEYS[1], 'nextSeq', 1)
            redis.call('XADD', KEYS[2], 'MAXLEN', '~', ARGV[5], '*', 'seq', seq, 'type', 'TURN_RESTARTED', 'text', ARGV[6])
            redis.call('EXPIRE', KEYS[2], ARGV[7])
            redis.call('EXPIRE', KEYS[1], ARGV[7])
            return 1
            """, Long.class);

    private static final DefaultRedisScript<Long> APPEND_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('HGET', KEYS[1], 'leaseOwner') ~= ARGV[1] then return -1 end
            local status = redis.call('HGET', KEYS[1], 'status')
            if status ~= 'RUNNING' and status ~= 'ACCEPTED' then return -1 end
            local seq = redis.call('HINCRBY', KEYS[1], 'nextSeq', 1)
            redis.call('XADD', KEYS[2], 'MAXLEN', '~', ARGV[5], '*', 'seq', seq, 'type', ARGV[2], 'text', ARGV[3])
            redis.call('EXPIRE', KEYS[2], ARGV[4])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            return seq
            """, Long.class);

    private static final DefaultRedisScript<Long> COMPLETE_SCRIPT = terminalScript("COMPLETED", true);

    private static final DefaultRedisScript<Long> FAIL_SCRIPT = terminalScript("FAILED", true);

    private static final DefaultRedisScript<Long> CANCEL_SCRIPT = terminalScript("CANCELLED", true);

    private static final DefaultRedisScript<Long> FAIL_EXPIRED_SCRIPT = new DefaultRedisScript<>("""
            local status = redis.call('HGET', KEYS[1], 'status')
            local now = tonumber(ARGV[2])
            local acceptedBefore = tonumber(ARGV[3])
            local deadline = redis.call('HGET', KEYS[1], 'leaseDeadline')
            local createdAt = tonumber(redis.call('HGET', KEYS[1], 'createdAt'))
            local expired = false
            if status == 'RUNNING' then
              if (not deadline) or deadline == '' or tonumber(deadline) <= now then expired = true end
            elseif status == 'ACCEPTED' then
              if createdAt and createdAt <= acceptedBefore then expired = true end
            end
            if not expired then return 0 end
            redis.call('HSET', KEYS[1], 'status', 'FAILED', 'errorCode', ARGV[1],
              'leaseOwner', '', 'leaseDeadline', '', 'updatedAt', ARGV[2])
            local seq = redis.call('HINCRBY', KEYS[1], 'nextSeq', 1)
            redis.call('XADD', KEYS[2], 'MAXLEN', '~', ARGV[4], '*', 'seq', seq, 'type', 'ERROR', 'text', ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[5])
            redis.call('EXPIRE', KEYS[2], ARGV[5])
            local sessionId = redis.call('HGET', KEYS[1], 'sessionId')
            local agentName = redis.call('HGET', KEYS[1], 'agentName')
            local activeKey = ARGV[6] .. sessionId
            if redis.call('GET', activeKey) == ARGV[8] then redis.call('DEL', activeKey) end
            redis.call('SREM', ARGV[7] .. agentName, ARGV[8])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    private final JdbcTurnRepository jdbc;

    private final Clock clock;

    private final long eventTtlSeconds;

    private final int eventMaxLen;

    public RedisTurnStore(StringRedisTemplate redis, JdbcTurnRepository jdbc, Clock clock,
                          long eventTtlSeconds, int eventMaxLen) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.clock = clock;
        this.eventTtlSeconds = eventTtlSeconds;
        this.eventMaxLen = eventMaxLen;
    }

    /**
     * 持有者匹配时写入终态和结束帧，并放开会话。
     * ARGV: owner, answerOrError, now, maxLen, ttl, activePrefix, openPrefix, turnId, frameType
     */
    private static DefaultRedisScript<Long> terminalScript(String status, boolean requireOwner) {
        String ownerCheck = requireOwner
                ? "if redis.call('HGET', KEYS[1], 'leaseOwner') ~= ARGV[1] then return 0 end "
                : "";
        String lua = ownerCheck + """
                local status = redis.call('HGET', KEYS[1], 'status')
                if status ~= 'RUNNING' and status ~= 'ACCEPTED' then return 0 end
                local answer = ARGV[2]
                local errorCode = ''
                local frameType = ARGV[9]
                if frameType == 'DONE' then
                  redis.call('HSET', KEYS[1], 'status', 'STATUS_PLACEHOLDER', 'finalAnswer', answer,
                    'errorCode', '', 'leaseOwner', '', 'leaseDeadline', '', 'updatedAt', ARGV[3])
                else
                  redis.call('HSET', KEYS[1], 'status', 'STATUS_PLACEHOLDER', 'errorCode', answer,
                    'leaseOwner', '', 'leaseDeadline', '', 'updatedAt', ARGV[3])
                end
                local seq = redis.call('HINCRBY', KEYS[1], 'nextSeq', 1)
                local text = answer
                if frameType == 'DONE' then text = '' end
                redis.call('XADD', KEYS[2], 'MAXLEN', '~', ARGV[4], '*', 'seq', seq, 'type', frameType, 'text', text)
                redis.call('EXPIRE', KEYS[1], ARGV[5])
                redis.call('EXPIRE', KEYS[2], ARGV[5])
                local sessionId = redis.call('HGET', KEYS[1], 'sessionId')
                local agentName = redis.call('HGET', KEYS[1], 'agentName')
                local activeKey = ARGV[6] .. sessionId
                if redis.call('GET', activeKey) == ARGV[8] then redis.call('DEL', activeKey) end
                redis.call('SREM', ARGV[7] .. agentName, ARGV[8])
                return 1
                """.replace("STATUS_PLACEHOLDER", status);
        return new DefaultRedisScript<>(lua, Long.class);
    }

    @Override
    public TurnBeginResult begin(TurnRecord draft) {
        String now = String.valueOf(clock.instant().toEpochMilli());
        String raw = redis.execute(BEGIN_SCRIPT, List.of(
                TurnRedisKeys.activeKey(draft.getSessionId()),
                TurnRedisKeys.turnKey(draft.getTurnId()),
                TurnRedisKeys.openKey(agentName(draft))),
                draft.getSessionId(),
                draft.getTurnId(),
                empty(draft.getUserId()),
                agentName(draft),
                draft.getQuery(),
                now,
                TurnRedisKeys.TURN_PREFIX);
        if (raw == null || (!raw.startsWith(REUSED_PREFIX) && !raw.startsWith(CREATED_PREFIX))) {
            throw new IllegalStateException("turn begin script returned no result, sessionId=" + draft.getSessionId());
        }
        if (raw.startsWith(REUSED_PREFIX)) {
            String turnId = raw.substring(REUSED_PREFIX.length());
            TurnBeginResult result = new TurnBeginResult();
            result.setReused(true);
            result.setTurn(find(turnId).orElseThrow(() -> new IllegalStateException(
                    "reused turn missing, turnId=" + turnId)));
            return result;
        }
        TurnRecord created = InMemoryTurnStore.copy(draft);
        created.setStatus(TurnStatusEnum.ACCEPTED);
        created.setCreatedAt(Instant.ofEpochMilli(Long.parseLong(now)));
        created.setUpdatedAt(created.getCreatedAt());
        try {
            jdbc.insert(created);
        } catch (RuntimeException ex) {
            redis.delete(List.of(TurnRedisKeys.turnKey(draft.getTurnId()),
                    TurnRedisKeys.activeKey(draft.getSessionId())));
            redis.opsForSet().remove(TurnRedisKeys.openKey(agentName(draft)), draft.getTurnId());
            throw ex;
        }
        TurnBeginResult result = new TurnBeginResult();
        result.setReused(false);
        result.setTurn(created);
        return result;
    }

    @Override
    public void markRunning(String turnId, String leaseOwner, Instant leaseDeadline) {
        long updated = executeLong(MARK_RUNNING_SCRIPT, List.of(TurnRedisKeys.turnKey(turnId)),
                leaseOwner, millis(leaseDeadline), millis(clock.instant()));
        if (updated == 0L) {
            throw new IllegalStateException("turn is not runnable, turnId=" + turnId);
        }
        mirror(turnId);
    }

    @Override
    public boolean renewLease(String turnId, String leaseOwner, Instant leaseDeadline) {
        return executeLong(RENEW_SCRIPT, List.of(TurnRedisKeys.turnKey(turnId)),
                leaseOwner, millis(leaseDeadline), millis(clock.instant())) > 0L;
    }

    @Override
    public boolean tryClaimExpired(String turnId, String leaseOwner, Instant leaseDeadline, Instant acceptedBefore) {
        long claimed = executeLong(CLAIM_SCRIPT, List.of(
                TurnRedisKeys.turnKey(turnId), TurnRedisKeys.eventsKey(turnId)),
                leaseOwner, millis(leaseDeadline), millis(clock.instant()), millis(acceptedBefore),
                String.valueOf(eventMaxLen), turnId, String.valueOf(eventTtlSeconds));
        if (claimed == 0L) {
            return false;
        }
        mirror(turnId);
        return true;
    }

    @Override
    public long appendEvent(String turnId, String leaseOwner, TurnFrameTypeEnum type, String text) {
        return executeLong(APPEND_SCRIPT, List.of(
                TurnRedisKeys.turnKey(turnId), TurnRedisKeys.eventsKey(turnId)),
                leaseOwner, type.getCode(), text == null ? "" : text, String.valueOf(eventTtlSeconds),
                String.valueOf(eventMaxLen));
    }

    @Override
    public boolean complete(String turnId, String leaseOwner, String answer) {
        return finish(COMPLETE_SCRIPT, turnId, leaseOwner, answer == null ? "" : answer,
                TurnFrameTypeEnum.DONE.getCode());
    }

    @Override
    public boolean fail(String turnId, String leaseOwner, String errorCode) {
        return finish(FAIL_SCRIPT, turnId, leaseOwner, errorCode, TurnFrameTypeEnum.ERROR.getCode());
    }

    @Override
    public boolean cancel(String turnId, String leaseOwner, String errorCode) {
        return finish(CANCEL_SCRIPT, turnId, leaseOwner, errorCode, TurnFrameTypeEnum.ERROR.getCode());
    }

    @Override
    public boolean failExpired(String turnId, String errorCode, Instant acceptedBefore) {
        long updated = executeLong(FAIL_EXPIRED_SCRIPT, List.of(
                TurnRedisKeys.turnKey(turnId), TurnRedisKeys.eventsKey(turnId)),
                errorCode, millis(clock.instant()), millis(acceptedBefore),
                String.valueOf(eventMaxLen), String.valueOf(eventTtlSeconds),
                TurnRedisKeys.ACTIVE_PREFIX, TurnRedisKeys.OPEN_PREFIX, turnId);
        if (updated == 0L) {
            return false;
        }
        mirror(turnId);
        return true;
    }

    @Override
    public Optional<TurnRecord> find(String turnId) {
        Optional<TurnRecord> live = readRedis(turnId);
        if (live.isPresent()) {
            return live;
        }
        return jdbc.find(turnId);
    }

    @Override
    public List<TurnRecord> listRecoverable(String agentName, Instant now, Instant acceptedBefore) {
        Set<String> ids = new HashSet<>();
        for (TurnRecord row : jdbc.listOpen(agentName)) {
            ids.add(row.getTurnId());
        }
        Set<String> members = redis.opsForSet().members(TurnRedisKeys.openKey(agentName));
        if (members != null) {
            ids.addAll(members);
        }
        List<TurnRecord> recoverable = new ArrayList<>();
        for (String turnId : ids) {
            TurnRecord turn = find(turnId).orElse(null);
            if (turn != null && claimable(turn, now, acceptedBefore)) {
                recoverable.add(turn);
            }
        }
        return recoverable;
    }

    @Override
    public List<TurnFrame> listEvents(String turnId, long afterSeq) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(
                TurnRedisKeys.eventsKey(turnId), Range.unbounded());
        if (records == null || records.isEmpty()) {
            return List.of();
        }
        List<TurnFrame> frames = new ArrayList<>();
        for (MapRecord<String, Object, Object> record : records) {
            TurnFrame frame = toFrame(turnId, record.getValue());
            if (frame != null && frame.getSeq() > afterSeq) {
                frames.add(frame);
            }
        }
        frames.sort(Comparator.comparingLong(TurnFrame::getSeq));
        return frames;
    }

    @Override
    public void markUnsafeTool(String turnId) {
        redis.opsForValue().set(TurnRedisKeys.unsafeKey(turnId), "1");
    }

    @Override
    public boolean hasUnsafeTool(String turnId) {
        return Boolean.TRUE.equals(redis.hasKey(TurnRedisKeys.unsafeKey(turnId)));
    }

    private boolean finish(DefaultRedisScript<Long> script, String turnId, String leaseOwner, String payload,
                           String frameType) {
        long updated = executeLong(script, List.of(
                TurnRedisKeys.turnKey(turnId), TurnRedisKeys.eventsKey(turnId)),
                leaseOwner, payload == null ? "" : payload, millis(clock.instant()),
                String.valueOf(eventMaxLen), String.valueOf(eventTtlSeconds),
                TurnRedisKeys.ACTIVE_PREFIX, TurnRedisKeys.OPEN_PREFIX, turnId, frameType);
        if (updated == 0L) {
            return false;
        }
        mirror(turnId);
        return true;
    }

    private void mirror(String turnId) {
        try {
            Optional<TurnRecord> live = readRedis(turnId);
            if (live.isEmpty()) {
                return;
            }
            jdbc.update(live.get());
        } catch (RuntimeException ex) {
            log.error("turn mirror failed, turnId={}", turnId, ex);
        }
    }

    private Optional<TurnRecord> readRedis(String turnId) {
        Map<Object, Object> entries = redis.opsForHash().entries(TurnRedisKeys.turnKey(turnId));
        if (entries.isEmpty()) {
            return Optional.empty();
        }
        TurnRecord turn = new TurnRecord();
        turn.setTurnId(text(entries, "turnId"));
        turn.setSessionId(text(entries, "sessionId"));
        turn.setUserId(blankToNull(text(entries, "userId")));
        turn.setAgentName(text(entries, "agentName"));
        turn.setQuery(text(entries, "query"));
        turn.setStatus(TurnStatusEnum.of(text(entries, "status")));
        turn.setLeaseOwner(blankToNull(text(entries, "leaseOwner")));
        turn.setLeaseDeadline(epoch(text(entries, "leaseDeadline")));
        turn.setErrorCode(blankToNull(text(entries, "errorCode")));
        turn.setFinalAnswer(blankToNull(text(entries, "finalAnswer")));
        turn.setCreatedAt(epoch(text(entries, "createdAt")));
        turn.setUpdatedAt(epoch(text(entries, "updatedAt")));
        return Optional.of(turn);
    }

    private TurnFrame toFrame(String turnId, Map<Object, Object> fields) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        TurnFrame frame = new TurnFrame();
        frame.setTurnId(turnId);
        frame.setSeq(Long.parseLong(String.valueOf(fields.get("seq"))));
        frame.setType(TurnFrameTypeEnum.of(String.valueOf(fields.get("type"))));
        Object text = fields.get("text");
        frame.setText(text == null ? "" : String.valueOf(text));
        return frame;
    }

    private long executeLong(DefaultRedisScript<Long> script, List<String> keys, String... args) {
        Long value = redis.execute(script, keys, (Object[]) args);
        return value == null ? 0L : value;
    }

    private static boolean claimable(TurnRecord record, Instant now, Instant acceptedBefore) {
        if (record.getStatus() == TurnStatusEnum.RUNNING) {
            Instant deadline = record.getLeaseDeadline();
            return deadline == null || !deadline.isAfter(now);
        }
        if (record.getStatus() == TurnStatusEnum.ACCEPTED) {
            Instant createdAt = record.getCreatedAt();
            return createdAt != null && !createdAt.isAfter(acceptedBefore);
        }
        return false;
    }

    private static String agentName(TurnRecord draft) {
        return draft.getAgentName() == null ? "" : draft.getAgentName();
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }

    private static String millis(Instant instant) {
        return String.valueOf(instant.toEpochMilli());
    }

    private static String text(Map<Object, Object> entries, String field) {
        Object value = entries.get(field);
        return value == null ? "" : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return StringUtils.isBlank(value) ? null : value;
    }

    private static Instant epoch(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        return Instant.ofEpochMilli(Long.parseLong(value));
    }
}
