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
package com.agentruntime.trace;

import com.agentruntime.api.enums.SpanTypeEnum;
import com.agentruntime.observability.trace.TraceTreeView;
import com.agentruntime.observability.trace.TraceTurnRef;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把调用树包进一个只读 HTML 页面。树文本先转义，再给节点标题着色。
 */
final class TracePage {

    /** 与渲染器节点前缀一致，只给标题着色，不给入出参里的同名文字着色 */
    private static final String BRANCH_MID = "├── ";

    private static final String BRANCH_LAST = "└── ";

    private static final String CLASS_MODEL = "model";

    private static final String CLASS_SKILL = "skill";

    private static final String CLASS_TOOL = "tool";

    private static final String COLOR_MODEL = "#1d4ed8";

    private static final String COLOR_SKILL = "#dc2626";

    private static final String COLOR_TOOL = "#047857";

    private static final Pattern NODE_TITLE = Pattern.compile(
            "(" + Pattern.quote(BRANCH_MID) + "|" + Pattern.quote(BRANCH_LAST) + ")("
                    + SpanTypeEnum.MODEL_CALL.getCode() + "|"
                    + SpanTypeEnum.TOOL_CALL.getCode() + "|"
                    + SpanTypeEnum.SKILL.getCode() + ")");

    private TracePage() {
    }

    static String render(String sessionId, TraceTreeView view) {
        TraceTreeView safeView = view == null ? new TraceTreeView() : view;
        String safeSession = sessionId == null ? "" : sessionId;
        StringBuilder html = new StringBuilder(2048);
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n");
        html.append("<title>Agent Runtime Trace</title>\n<style>\n");
        html.append("body{font-family:sans-serif;margin:24px;color:#1f2328}\n");
        html.append("form{margin:16px 0}input{font:inherit;padding:4px 8px;min-width:280px}\n");
        html.append("nav a,nav span{margin-right:12px}.current{font-weight:600}\n");
        html.append(".notice{color:#9a3412}\n");
        html.append("pre{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;");
        html.append("background:#f6f8fa;padding:16px;overflow:auto;line-height:1.45}\n");
        html.append("pre .").append(CLASS_MODEL).append("{color:").append(COLOR_MODEL).append(";font-weight:600}\n");
        html.append("pre .").append(CLASS_SKILL).append("{color:").append(COLOR_SKILL).append(";font-weight:600}\n");
        html.append("pre .").append(CLASS_TOOL).append("{color:").append(COLOR_TOOL).append(";font-weight:600}\n");
        html.append("</style>\n</head>\n<body>\n<h1>Agent Runtime Trace</h1>\n");
        html.append("<form method=\"get\" action=\"").append(TraceEndpointConstants.PATH).append("\">\n");
        html.append("<label>sessionId <input name=\"").append(TraceEndpointConstants.PARAM_SESSION_ID);
        html.append("\" value=\"").append(escape(safeSession)).append("\"></label>\n");
        html.append("<button type=\"submit\">Open</button>\n</form>\n");
        appendTurns(html, safeSession, safeView);
        if (StringUtils.hasText(safeView.getNotice())) {
            html.append("<p class=\"notice\">").append(escape(safeView.getNotice())).append("</p>\n");
        }
        html.append("<pre>");
        if (safeView.getTree() != null) {
            html.append(colorNodeTitles(escape(safeView.getTree())));
        }
        html.append("</pre>\n</body>\n</html>\n");
        return html.toString();
    }

    static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(current);
            }
        }
        return out.toString();
    }

    private static void appendTurns(StringBuilder html, String sessionId, TraceTreeView view) {
        if (!StringUtils.hasText(sessionId) || CollectionUtils.isEmpty(view.getTurns())) {
            return;
        }
        html.append("<nav>\n");
        for (TraceTurnRef turn : view.getTurns()) {
            String label = "turn " + turn.getIndex();
            if (StringUtils.hasText(turn.getSpanId()) && turn.getSpanId().equals(view.getSelectedTurnId())) {
                html.append("<span class=\"current\">").append(escape(label)).append("</span>\n");
                continue;
            }
            String href = TraceEndpointConstants.PATH
                    + "?" + TraceEndpointConstants.PARAM_SESSION_ID + "=" + url(sessionId)
                    + "&" + TraceEndpointConstants.PARAM_TURN + "=" + url(turn.getSpanId());
            html.append("<a href=\"").append(escape(href)).append("\">");
            html.append(escape(label)).append("</a>\n");
        }
        html.append("</nav>\n");
    }

    private static String url(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String colorNodeTitles(String escapedTree) {
        Matcher matcher = NODE_TITLE.matcher(escapedTree);
        StringBuilder colored = new StringBuilder(escapedTree.length());
        while (matcher.find()) {
            String title = "<span class=\"" + cssClass(matcher.group(2)) + "\">" + matcher.group(2) + "</span>";
            matcher.appendReplacement(colored, Matcher.quoteReplacement(matcher.group(1) + title));
        }
        matcher.appendTail(colored);
        return colored.toString();
    }

    private static String cssClass(String spanType) {
        if (SpanTypeEnum.MODEL_CALL.getCode().equals(spanType)) {
            return CLASS_MODEL;
        }
        if (SpanTypeEnum.SKILL.getCode().equals(spanType)) {
            return CLASS_SKILL;
        }
        return CLASS_TOOL;
    }
}
