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

import java.util.ArrayList;
import java.util.List;

/**
 * 一次 trace 查询的展示结果。{@code tree} 是纯文本，由页面负责转义后放入等宽区域。
 */
public class TraceTreeView {

    /** 当前轮的调用树。没有可画的树时为空字符串 */
    private String tree = "";

    /** 会话内全部轮次，按开始时间升序 */
    private List<TraceTurnRef> turns = new ArrayList<>();

    /** 当前展开的 TURN span id。未选中时为空 */
    private String selectedTurnId;

    /** 会话不存在、没有轮次、或指定轮次找不到时的说明 */
    private String notice;

    public String getTree() {
        return tree;
    }

    public void setTree(String tree) {
        this.tree = tree;
    }

    public List<TraceTurnRef> getTurns() {
        return turns;
    }

    public void setTurns(List<TraceTurnRef> turns) {
        this.turns = turns;
    }

    public String getSelectedTurnId() {
        return selectedTurnId;
    }

    public void setSelectedTurnId(String selectedTurnId) {
        this.selectedTurnId = selectedTurnId;
    }

    public String getNotice() {
        return notice;
    }

    public void setNotice(String notice) {
        this.notice = notice;
    }
}
