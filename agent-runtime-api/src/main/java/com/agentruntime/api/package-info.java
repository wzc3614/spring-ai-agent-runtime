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
 * Contracts layer of the Agent Runtime Harness.
 *
 * <p>Contains domain models, data-access repositories, capability SPI ports,
 * configuration classes, enums and exceptions. This module intentionally has no
 * Spring AI dependency so it can be depended on independently by both the core
 * runtime and external integrations.
 *
 * <p>Subpackages:
 * <ul>
 *   <li>{@code domain} &mdash; AgentSession, AgentMessage, SummarySnapshot, SummaryContent,
 *       ToolCall, Span, EventRecord</li>
 *   <li>{@code enums} &mdash; status / role / risk / failure / span / event / compaction enums</li>
 *   <li>{@code repository} &mdash; data-access ports (xxRepository) backed by the agent_* tables</li>
 *   <li>{@code spi} &mdash; capability extension ports: TokenEstimator, ToolFailureClassifier,
 *       TransactionPort, ToolResultCompactor</li>
 *   <li>{@code lifecycle} &mdash; AgentLifecycleEvent, AgentLifecycleTypeEnum, AgentLifecycleListener</li>
 *   <li>{@code memory} &mdash; CompactConfig, ToolCompactionRule, ToolCompactionPolicyRegistry</li>
 *   <li>{@code exception} &mdash; AgentRuntimeException, OptimisticLockException</li>
 * </ul>
 */
package com.agentruntime.api;
