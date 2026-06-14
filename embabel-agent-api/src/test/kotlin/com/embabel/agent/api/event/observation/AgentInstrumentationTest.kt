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
@file:OptIn(com.embabel.agent.api.event.observation.InternalObservabilityApi::class)

package com.embabel.agent.api.event.observation

import io.micrometer.observation.Observation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.Test

/**
 * [NoOpAgentInstrumentation] is the default at the agent work sites whenever no observability module
 * contributes an [AgentInstrumentation] adapter. It must run the work, return its value (including
 * `null`), propagate exceptions unchanged, and create **no observation at all** — not even building
 * the context. This is what makes "no module = no embabel spans" structural rather than a flag.
 */
class AgentInstrumentationTest {

    @Test
    fun `NoOp runs work and returns its value`() {
        val result = NoOpAgentInstrumentation.observe({ Observation.Context() }) { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `NoOp creates no observation - it never builds the context`() {
        var contextBuilt = false
        val result = NoOpAgentInstrumentation.observe(
            { contextBuilt = true; Observation.Context() },
        ) { "done" }
        assertEquals("done", result)
        assertFalse(contextBuilt, "NoOp must not build the context: no observation is created")
    }

    @Test
    fun `NoOp propagates a null result rather than throwing`() {
        val result: String? = NoOpAgentInstrumentation.observe({ Observation.Context() }) { null }
        assertNull(result)
    }

    @Test
    fun `NoOp propagates the original exception`() {
        val boom = IllegalStateException("boom")
        val thrown = assertThrows<IllegalStateException> {
            NoOpAgentInstrumentation.observe({ Observation.Context() }) { throw boom }
        }
        assertEquals(boom, thrown)
    }
}
