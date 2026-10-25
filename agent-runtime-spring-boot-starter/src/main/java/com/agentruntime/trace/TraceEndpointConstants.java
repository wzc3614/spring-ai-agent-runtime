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

/**
 * 单次请求调用树页面的路径和开关。默认关闭。
 */
public final class TraceEndpointConstants {

    /** 打开页面的配置项。未配置或非 true 时不注册端点 */
    public static final String ENABLED_PROPERTY = "agent.runtime.trace.enabled";

    /** 只读页面路径 */
    public static final String PATH = "/agent-runtime/trace";

    /** 会话参数 */
    public static final String PARAM_SESSION_ID = "sessionId";

    /** 指定 TURN span id。空白时展示该会话最近一轮 */
    public static final String PARAM_TURN = "turn";

    /** 响应类型 */
    public static final String MEDIA_TYPE_HTML = "text/html;charset=UTF-8";

    private TraceEndpointConstants() {
    }
}
