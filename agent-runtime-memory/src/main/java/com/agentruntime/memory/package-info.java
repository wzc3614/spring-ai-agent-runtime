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
 * Progressive memory compaction subsystem.
 *
 * <p>Implements the read/write split philosophy: the write side is a fast, unprocessed
 * append of every message; all compaction decisions are deferred to the read (inject)
 * side. Provides three layers of defence: full persistence, tool-result micro-compaction
 * and nine-section LLM summary with incremental merge-update, plus a dynamic budget
 * calculator and a local token estimator. Also hosts session persistence and the Flyway
 * migration scripts for the whole {@code agent_*} schema.
 */
package com.agentruntime.memory;
