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

/**
 * Cognitive observability subsystem.
 *
 * <p>Captures the reasoning process, not just the call chain: a Span tree
 * (Session / Turn / ModelCall / ToolCall) with Events for Thinking, FinalReply,
 * ToolInput/Output and Errors. Defaults to native persistence in an independent schema
 * of the same database (pluggable to ClickHouse / Elasticsearch) and can additionally
 * export via OpenTelemetry / OTLP to existing Jaeger / Zipkin / SkyWalking / Tempo stacks.
 */
package com.agentruntime.observability;
