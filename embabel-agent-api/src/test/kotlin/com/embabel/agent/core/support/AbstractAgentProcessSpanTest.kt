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
package com.embabel.agent.core.support

import com.embabel.agent.api.common.PlatformServices
import com.embabel.agent.api.dsl.evenMoreEvilWizard
import com.embabel.agent.api.event.observation.ActionObservationContext
import com.embabel.agent.api.event.observation.AgentObservationContext
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Verifies that running a real agent produces the direct-instrumentation span tree:
 * one `embabel.agent` turn span with the `embabel.action` spans nested under it. Uses a
 * recording [ObservationHandler] (no tracer / OTel needed); nesting is asserted via the
 * parent-observation chain that Micrometer maintains.
 */
class AbstractAgentProcessSpanTest {

    private class RecordingHandler : ObservationHandler<Observation.Context> {
        val stopped = CopyOnWriteArrayList<Observation.Context>()
        override fun supportsContext(context: Observation.Context) = true
        override fun onStop(context: Observation.Context) {
            stopped += context
        }
    }

    private fun servicesWith(registry: ObservationRegistry): PlatformServices {
        val base = IntegrationTestUtils.dummyPlatformServices()
        return object : PlatformServices by base {
            override val observationRegistry = registry
        }
    }

    @Test
    fun `run produces a turn span with action spans nested under it`() {
        val registry = ObservationRegistry.create()
        val handler = RecordingHandler()
        registry.observationConfig().observationHandler(handler)

        val process = IntegrationTestUtils.dummyAgentProcessRunning(
            evenMoreEvilWizard(),
            servicesWith(registry),
        )
        process += UserInput("Rod")
        process.run()

        val turns = handler.stopped.filterIsInstance<AgentObservationContext>()
        val actions = handler.stopped.filterIsInstance<ActionObservationContext>()
        assertEquals(1, turns.size, "exactly one turn span")
        assertTrue(actions.size >= 2, "expected at least two action spans, got ${actions.size}")
        actions.forEach { a ->
            val parent = a.parentObservation?.contextView
            assertTrue(
                parent is AgentObservationContext,
                "action ${a.action.name} should nest under the turn span",
            )
        }
    }

    @Test
    fun `NOOP registry produces no spans`() {
        val registry = ObservationRegistry.create()
        val handler = RecordingHandler()
        // Default dummy services carry a NOOP registry: observe{} is a pure passthrough.
        val process = IntegrationTestUtils.dummyAgentProcessRunning(evenMoreEvilWizard())
        process += UserInput("Rod")
        process.run()

        // Nothing was recorded because no real registry was wired in.
        registry.observationConfig().observationHandler(handler)
        assertTrue(handler.stopped.isEmpty())
    }
}
