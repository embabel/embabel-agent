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
import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.test.integration.IntegrationTestUtils
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Verifies that running a real agent produces the direct-instrumentation span tree:
 * one `embabel.agent` turn span with the `embabel.action` spans nested under it. Uses a
 * recording [ObservationHandler] (no tracer / OTel needed); nesting is asserted via the
 * parent-observation chain that Micrometer maintains.
 */
class AgentProcessSpanTest {

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

    /** A real registry wired to a fresh [RecordingHandler] that captures every closed span. */
    private fun recordingRegistry(): Pair<ObservationRegistry, RecordingHandler> {
        val registry = ObservationRegistry.create()
        val handler = RecordingHandler()
        registry.observationConfig().observationHandler(handler)
        return registry to handler
    }

    /** Run the wizard fixture against [registry], returning the completed process. */
    private fun runWizard(registry: ObservationRegistry): AgentProcess {
        val process = IntegrationTestUtils.dummyAgentProcessRunning(
            evenMoreEvilWizard(),
            servicesWith(registry),
        )
        process += UserInput("Rod")
        return process.run()
    }

    @Test
    fun `run produces a turn span with action spans nested under it`() {
        val (registry, handler) = recordingRegistry()
        runWizard(registry)

        val turns = handler.stopped.filterIsInstance<AgentObservationContext>()
        val actions = handler.stopped.filterIsInstance<ActionObservationContext>()
        assertEquals(1, turns.size, "exactly one turn span")
        // EvenMoreEvilWizard runs a deterministic set of actions: the "thing"
        // transformation plus the aggregate's three transforms and its merge.
        assertEquals(5, actions.size, "expected exactly five action spans, got ${actions.size}")
        actions.forEach { a ->
            val parent = a.parentObservation?.contextView
            assertTrue(
                parent is AgentObservationContext,
                "action ${a.action.name} should nest under the turn span",
            )
        }
    }

    @Test
    fun `action spans carry the resulting status code, captured after execution`() {
        val (registry, handler) = recordingRegistry()
        runWizard(registry)

        val actions = handler.stopped.filterIsInstance<ActionObservationContext>()
        assertTrue(actions.isNotEmpty(), "expected at least one action span")
        actions.forEach { a ->
            assertNotNull(
                a.statusCode,
                "action ${a.action.name} must carry its result status by stop time",
            )
        }
    }

    @Test
    fun `a turn that throws still closes its span and marks it errored`() {
        val (registry, handler) = recordingRegistry()

        // A GOAP process with no goals throws in executeTurn; the throw must propagate through
        // observe{}, which closes the span and errors it (the anti-scope-leak guarantee).
        val goalless = Agent(
            name = "goalless",
            provider = "test",
            description = "agent with no goals",
            actions = emptyList(),
            goals = emptySet(),
        )
        val process = IntegrationTestUtils.dummyAgentProcessRunning(goalless, servicesWith(registry))

        assertThrows<IllegalStateException> { process.run() }

        val turns = handler.stopped.filterIsInstance<AgentObservationContext>()
        assertEquals(1, turns.size, "the failed turn must still close exactly one span")
        assertNotNull(turns.single().error, "a turn that throws must mark its span errored")
    }

    @Test
    fun `a NOOP registry runs the work as a transparent passthrough`() {
        // Default dummy services carry ObservationRegistry.NOOP, so observe{} must skip all
        // instrumentation yet still execute the work and drive the run to completion.
        val process = IntegrationTestUtils.dummyAgentProcessRunning(evenMoreEvilWizard())
        process += UserInput("Rod")
        process.run()

        assertEquals(
            AgentProcessStatusCode.COMPLETED,
            process.status,
            "NOOP passthrough must still run the agent to completion",
        )
        assertTrue(
            process.history.isNotEmpty(),
            "NOOP passthrough must still execute the agent's actions",
        )
    }
}
