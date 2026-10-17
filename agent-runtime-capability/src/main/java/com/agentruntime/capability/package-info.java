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
 * Capability supply subsystem.
 *
 * <p>Two halves: skill loading (classpath built-in + DB dynamic, where DB overrides
 * same-name built-in skills and takes effect on the next spawn) and MCP / tool
 * hot-plug driven by a config-center SPI (default Nacos, with Apollo and a
 * zero-dependency local-file fallback). Hot updates are safe via diff refresh and
 * reference-counting delayed close so in-flight calls finish before a retired tool
 * connection is released.
 */
package com.agentruntime.capability;
