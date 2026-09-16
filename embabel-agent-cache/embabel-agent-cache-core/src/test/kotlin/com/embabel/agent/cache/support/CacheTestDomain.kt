/*
 * Copyright 2024-2026 Embabel Pty Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.embabel.agent.cache.support

/*
 * Domain types for cache tests.
 *
 * Defined here rather than taken from embabel-agent-test-internal. That module
 * depends, through embabel-agent-test and embabel-agent-starter, on platform
 * auto-configuration, which depends on this module for cache-backed persistence.
 * A test dependency on it would be a reactor cycle.
 */

data class MagicVictim(
    val name: String,
)

data class Frog(
    val name: String,
)
