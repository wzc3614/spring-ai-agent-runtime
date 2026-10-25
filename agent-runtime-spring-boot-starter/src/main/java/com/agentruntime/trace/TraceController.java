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

import com.agentruntime.observability.trace.TraceTreeView;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单次请求调用树的只读页面。不改 span，也不计算费用。
 */
@RestController
public class TraceController {

    private final TraceTreeService traceTreeService;

    public TraceController(TraceTreeService traceTreeService) {
        this.traceTreeService = traceTreeService;
    }

    /**
     * 打开调用树。未带 sessionId 时只返回查询表单。
     *
     * @param sessionId 会话 ID
     * @param turn      TURN span id。空白时展示最近一轮
     * @return HTML 页面
     */
    @GetMapping(path = TraceEndpointConstants.PATH, produces = TraceEndpointConstants.MEDIA_TYPE_HTML)
    public String page(@RequestParam(value = TraceEndpointConstants.PARAM_SESSION_ID, required = false) String sessionId,
                       @RequestParam(value = TraceEndpointConstants.PARAM_TURN, required = false) String turn) {
        if (!StringUtils.hasText(sessionId)) {
            return TracePage.render("", new TraceTreeView());
        }
        return TracePage.render(sessionId, traceTreeService.load(sessionId, turn));
    }
}
