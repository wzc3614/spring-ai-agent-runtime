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
package com.agentruntime.observability.trace;

import com.agentruntime.api.domain.AgentSession;
import com.agentruntime.api.domain.EventRecord;
import com.agentruntime.api.domain.Span;
import com.agentruntime.api.enums.EventTypeEnum;
import com.agentruntime.api.enums.SpanTypeEnum;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把一次用户请求的 span / event 画成文本树。只展开选中的 TURN 及其子孙，
 * 父 span 为空的压缩 span 不挂进这棵树。
 */
public class TraceTreeRenderer {

    private static final String BRANCH_LAST = "└── ";

    private static final String BRANCH_MID = "├── ";

    private static final String GUIDE = "│";

    private static final String PAD_LAST = "    ";

    private static final String PAD_MID = "│   ";

    private static final TypeReference<Map<String, Object>> ATTR_TYPE = new TypeReference<>() {
    };

    private static final Comparator<Span> BY_START = Comparator
            .comparing(Span::getStartedAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Span::getSpanId, Comparator.nullsLast(Comparator.naturalOrder()));

    private static final Comparator<EventRecord> BY_OCCURRED = Comparator
            .comparing(EventRecord::getOccurredAt, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(EventRecord::getEventId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final ObjectMapper objectMapper;

    public TraceTreeRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 渲染一棵调用树。
     *
     * @param session         会话，不存在时传 null
     * @param spans           该 session 的全部 span
     * @param eventsBySpan    spanId 到事件列表。缺少的 span 视为没有事件
     * @param requestedTurnId 指定 TURN span id。空白时选开始时间最晚的一轮
     * @return 展示结果
     */
    public TraceTreeView render(AgentSession session, List<Span> spans,
                                Map<String, List<EventRecord>> eventsBySpan, String requestedTurnId) {
        List<Span> safeSpans = spans == null ? List.of() : spans;
        TraceTreeView view = new TraceTreeView();
        if (session == null && safeSpans.isEmpty()) {
            view.setNotice(TraceTreeConstants.NOTICE_SESSION_NOT_FOUND);
            return view;
        }
        String sessionId = sessionId(session, safeSpans);
        List<TraceTurnRef> turns = turnRefs(safeSpans);
        view.setTurns(turns);
        StringBuilder tree = new StringBuilder();
        tree.append(sessionLine(session, sessionId)).append('\n');
        Span selected = selectTurn(turns, safeSpans, requestedTurnId, view);
        if (selected == null) {
            view.setTree(tree.toString());
            return view;
        }
        view.setSelectedTurnId(selected.getSpanId());
        RenderState state = new RenderState(childrenByParent(safeSpans), eventsBySpan, finalReplies(selected, eventsBySpan));
        appendChildren(tree, "", List.of(selected), state);
        view.setTree(tree.toString());
        return view;
    }

    private Span selectTurn(List<TraceTurnRef> turns, List<Span> spans, String requestedTurnId, TraceTreeView view) {
        if (turns.isEmpty()) {
            view.setNotice(TraceTreeConstants.NOTICE_NO_TURN);
            return null;
        }
        if (!StringUtils.hasText(requestedTurnId)) {
            return spanById(spans, turns.get(turns.size() - 1).getSpanId());
        }
        for (TraceTurnRef turn : turns) {
            if (requestedTurnId.equals(turn.getSpanId())) {
                return spanById(spans, turn.getSpanId());
            }
        }
        view.setNotice(TraceTreeConstants.NOTICE_TURN_NOT_FOUND);
        return null;
    }

    private void appendNode(StringBuilder tree, String prefix, boolean last, Span span, RenderState state) {
        tree.append(prefix).append(last ? BRANCH_LAST : BRANCH_MID).append(title(span, state)).append('\n');
        String childPrefix = prefix + (last ? PAD_LAST : PAD_MID);
        for (String line : details(span, state.eventsOf(span.getSpanId()), state)) {
            tree.append(childPrefix).append(line).append('\n');
        }
        appendChildren(tree, childPrefix, state.childrenOf(span.getSpanId()), state);
    }

    private void appendChildren(StringBuilder tree, String prefix, List<Span> children, RenderState state) {
        if (CollectionUtils.isEmpty(children)) {
            return;
        }
        tree.append(prefix).append(GUIDE).append('\n');
        for (int i = 0; i < children.size(); i++) {
            boolean last = i == children.size() - 1;
            appendNode(tree, prefix, last, children.get(i), state);
            if (!last) {
                tree.append(prefix).append(GUIDE).append('\n');
            }
        }
    }

    private String title(Span span, RenderState state) {
        if (span.getSpanType() == SpanTypeEnum.MODEL_CALL) {
            state.modelCallIndex++;
            return SpanTypeEnum.MODEL_CALL.getCode() + " #" + state.modelCallIndex;
        }
        if (span.getSpanType() == null) {
            return "SPAN";
        }
        return span.getSpanType().getCode();
    }

    private List<String> details(Span span, List<EventRecord> events, RenderState state) {
        List<String> lines = new ArrayList<>();
        Map<String, Object> attrs = attributes(span);
        if (span.getSpanType() == SpanTypeEnum.TURN) {
            appendNarrative(lines, TraceTreeConstants.LABEL_RESPONSE, events, EventTypeEnum.FINAL_REPLY, Set.of());
        } else if (span.getSpanType() == SpanTypeEnum.MODEL_CALL) {
            appendNarrative(lines, TraceTreeConstants.LABEL_THINKING, events, EventTypeEnum.THINKING, state.finalReplies);
            appendAttr(lines, "modelName", attrs.get(TraceTreeConstants.ATTR_MODEL_NAME), true);
            appendAttr(lines, "finishReason", attrs.get(TraceTreeConstants.ATTR_FINISH_REASON), true);
            appendTokens(lines, attrs);
        } else if (span.getSpanType() == SpanTypeEnum.TOOL_CALL) {
            appendAttr(lines, "toolName", attrs.get(TraceTreeConstants.ATTR_TOOL_NAME), true);
            appendPayload(lines, "input", events, EventTypeEnum.TOOL_INPUT);
            appendPayload(lines, "output", events, EventTypeEnum.TOOL_OUTPUT);
        } else {
            appendRemainingAttrs(lines, attrs);
        }
        appendDuration(lines, span);
        appendPayload(lines, "error", events, EventTypeEnum.ERROR);
        appendPayload(lines, "budget", events, EventTypeEnum.TURN_BUDGET_EXCEEDED);
        return lines;
    }

    private void appendRemainingAttrs(List<String> lines, Map<String, Object> attrs) {
        for (Map.Entry<String, Object> entry : attrs.entrySet()) {
            boolean quote = !(entry.getValue() instanceof Number);
            appendAttr(lines, entry.getKey(), entry.getValue(), quote);
        }
    }

    private void appendTokens(List<String> lines, Map<String, Object> attrs) {
        Object input = attrs.get(TraceTreeConstants.ATTR_INPUT_TOKENS);
        Object output = attrs.get(TraceTreeConstants.ATTR_OUTPUT_TOKENS);
        if (input == null && output == null) {
            return;
        }
        StringBuilder line = new StringBuilder();
        if (input != null) {
            line.append("inputTokens=").append(plain(input));
        }
        if (output != null) {
            if (!line.isEmpty()) {
                line.append(", ");
            }
            line.append("outputTokens=").append(plain(output));
        }
        lines.add(line.toString());
    }

    private void appendAttr(List<String> lines, String name, Object value, boolean quote) {
        if (value == null) {
            return;
        }
        String text = quote ? quoted(plain(value)) : plain(value);
        if (!StringUtils.hasText(text) || "\"\"".equals(text)) {
            return;
        }
        lines.add(name + "=" + text);
    }

    private void appendPayload(List<String> lines, String name, List<EventRecord> events, EventTypeEnum type) {
        if (CollectionUtils.isEmpty(events)) {
            return;
        }
        for (EventRecord event : events) {
            if (event.getEventType() != type || !StringUtils.hasText(event.getPayload())) {
                continue;
            }
            lines.add(name + "=" + preview(event.getPayload()));
        }
    }

    private void appendNarrative(List<String> lines, String name, List<EventRecord> events, EventTypeEnum type,
                                 Set<String> skipEqualTo) {
        if (CollectionUtils.isEmpty(events)) {
            return;
        }
        for (EventRecord event : events) {
            if (event.getEventType() != type || !StringUtils.hasText(event.getPayload())) {
                continue;
            }
            if (skipEqualTo.contains(event.getPayload())) {
                continue;
            }
            String[] parts = narrative(event.getPayload()).split("\n", -1);
            lines.add(name + "=" + parts[0]);
            for (int i = 1; i < parts.length; i++) {
                lines.add(parts[i]);
            }
        }
    }

    private Set<String> finalReplies(Span turn, Map<String, List<EventRecord>> eventsBySpan) {
        Set<String> replies = new HashSet<>();
        if (turn == null || eventsBySpan == null) {
            return replies;
        }
        List<EventRecord> events = eventsBySpan.get(turn.getSpanId());
        if (CollectionUtils.isEmpty(events)) {
            return replies;
        }
        for (EventRecord event : events) {
            if (event.getEventType() == EventTypeEnum.FINAL_REPLY && StringUtils.hasText(event.getPayload())) {
                replies.add(event.getPayload());
            }
        }
        return replies;
    }

    private void appendDuration(List<String> lines, Span span) {
        if (span.getStartedAt() == null || span.getEndedAt() == null) {
            return;
        }
        long millis = Duration.between(span.getStartedAt(), span.getEndedAt()).toMillis();
        lines.add("durationMs=" + Math.max(millis, 0L));
    }

    private Map<String, Object> attributes(Span span) {
        if (span == null || !StringUtils.hasText(span.getAttributes())) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(span.getAttributes(), ATTR_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private String sessionLine(AgentSession session, String sessionId) {
        StringBuilder line = new StringBuilder("SESSION");
        if (session != null) {
            appendHeaderField(line, "agentName", session.getAgentName());
            appendHeaderField(line, "userId", session.getUserId());
        }
        appendHeaderField(line, "sessionId", sessionId);
        return line.toString();
    }

    private void appendHeaderField(StringBuilder line, String name, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        line.append("  ").append(name).append('=').append(quoted(value));
    }

    private List<TraceTurnRef> turnRefs(List<Span> spans) {
        List<Span> turns = new ArrayList<>();
        for (Span span : spans) {
            if (span.getSpanType() == SpanTypeEnum.TURN) {
                turns.add(span);
            }
        }
        turns.sort(BY_START);
        List<TraceTurnRef> refs = new ArrayList<>(turns.size());
        for (int i = 0; i < turns.size(); i++) {
            Span turn = turns.get(i);
            TraceTurnRef ref = new TraceTurnRef();
            ref.setSpanId(turn.getSpanId());
            ref.setStartedAt(turn.getStartedAt());
            ref.setIndex(i + 1);
            refs.add(ref);
        }
        return refs;
    }

    private Map<String, List<Span>> childrenByParent(List<Span> spans) {
        Map<String, List<Span>> children = new HashMap<>();
        for (Span span : spans) {
            if (!StringUtils.hasText(span.getParentSpanId())) {
                continue;
            }
            children.computeIfAbsent(span.getParentSpanId(), id -> new ArrayList<>()).add(span);
        }
        for (List<Span> group : children.values()) {
            group.sort(BY_START);
        }
        return children;
    }

    private Span spanById(List<Span> spans, String spanId) {
        if (!StringUtils.hasText(spanId)) {
            return null;
        }
        for (Span span : spans) {
            if (spanId.equals(span.getSpanId())) {
                return span;
            }
        }
        return null;
    }

    private String sessionId(AgentSession session, List<Span> spans) {
        if (session != null && StringUtils.hasText(session.getSessionId())) {
            return session.getSessionId();
        }
        for (Span span : spans) {
            if (StringUtils.hasText(span.getSessionId())) {
                return span.getSessionId();
            }
        }
        return null;
    }

    private String quoted(String value) {
        String preview = preview(value);
        return "\"" + preview.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String plain(Object value) {
        return preview(String.valueOf(value));
    }

    private String preview(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        if (flat.length() <= TraceTreeConstants.MAX_VALUE_CHARS) {
            return flat;
        }
        return flat.substring(0, TraceTreeConstants.MAX_VALUE_CHARS) + TraceTreeConstants.TRUNCATED_SUFFIX;
    }

    private String narrative(String value) {
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        while (normalized.endsWith("\n")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.length() <= TraceTreeConstants.MAX_NARRATIVE_CHARS) {
            return normalized;
        }
        return normalized.substring(0, TraceTreeConstants.MAX_NARRATIVE_CHARS) + TraceTreeConstants.TRUNCATED_SUFFIX;
    }

    /** 一次渲染的可变计数和索引。渲染器实例本身不保存请求状态。 */
    private static final class RenderState {

        private final Map<String, List<Span>> children;

        private final Map<String, List<EventRecord>> eventsBySpan;

        private final Set<String> finalReplies;

        private int modelCallIndex;

        private RenderState(Map<String, List<Span>> children, Map<String, List<EventRecord>> eventsBySpan,
                            Set<String> finalReplies) {
            this.children = children;
            this.eventsBySpan = eventsBySpan == null ? Map.of() : eventsBySpan;
            this.finalReplies = finalReplies == null ? Set.of() : finalReplies;
        }

        private List<Span> childrenOf(String spanId) {
            List<Span> found = children.get(spanId);
            return found == null ? List.of() : found;
        }

        private List<EventRecord> eventsOf(String spanId) {
            List<EventRecord> found = eventsBySpan.get(spanId);
            if (CollectionUtils.isEmpty(found)) {
                return List.of();
            }
            List<EventRecord> sorted = new ArrayList<>(found);
            sorted.sort(BY_OCCURRED);
            return sorted;
        }
    }
}
