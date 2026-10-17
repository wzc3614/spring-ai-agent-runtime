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
 * Human-in-the-Loop (L2) extension.
 *
 * <p>Models an approval as an intentional dangling tool_call, reusing the L1
 * persistence and dangling-repair machinery. A high-risk tool call is intercepted by
 * the HIL Advisor, persisted as a pending tool_call, exposed through a REST approval
 * API (V1 scope: API only, no IM; an {@code ApprovalNotifier} SPI is reserved for
 * downstream IM integrations), and resumed by writing the decision back as a
 * ToolMessage. The risk-level policy hook upgrades the demo's RiskPre/PostCheck.
 */
package com.agentruntime.hil;
