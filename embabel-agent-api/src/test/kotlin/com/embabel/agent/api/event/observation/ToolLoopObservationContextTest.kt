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
package com.embabel.agent.api.event.observation

import com.embabel.agent.api.event.ToolLoopStartEvent
import io.micrometer.common.KeyValues
import io.micrometer.observation.GlobalObservationConvention
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Locks the temporal contract of the mutable [ToolLoopObservationContext.output]: the result is
 * computed *during* the observed work (it is not on the start event), so it must be null when the
 * convention reads the context at span start and present when re-read at span stop.
 */
class ToolLoopObservationContextTest {

    /** Records the value of [ToolLoopObservationContext.output] each time the span is read. */
    private class OutputProbeConvention : GlobalObservationConvention<ToolLoopObservationContext> {
        val outputsSeen = CopyOnWriteArrayList<Any?>()
        override fun supportsContext(context: Observation.Context) = context is ToolLoopObservationContext
        override fun getName() = "embabel.tool_loop"
        override fun getHighCardinalityKeyValues(context: ToolLoopObservationContext): KeyValues {
            outputsSeen.add(context.output)
            return KeyValues.empty()
        }
    }

    /** Required to make the observation active: without a handler that supports the context, the
     * registry would not run start/stop and the convention would never be invoked. */
    private object NoopHandler : ObservationHandler<Observation.Context> {
        override fun supportsContext(context: Observation.Context) = true
    }

    @Test
    fun `output is null at start and captured when the convention re-reads at stop`() {
        val probe = OutputProbeConvention()
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(NoopHandler).observationConvention(probe)
        val context = ToolLoopObservationContext(mockk<ToolLoopStartEvent>(), emptyList())

        Observations.observeOrSkip(registry, { context }) {
            context.output = "loop answer" // result known only once the work has run
            "loop answer"
        }

        // Assert the temporal contract via first/last rather than an exact list, so the test stays
        // robust to how many times Micrometer happens to read the convention.
        assertNull(probe.outputsSeen.first(), "convention must see no output at span start")
        assertEquals(
            "loop answer",
            probe.outputsSeen.last(),
            "convention must see the output captured by span stop",
        )
    }

    @Test
    fun `output stays null when the work throws before producing a result`() {
        val probe = OutputProbeConvention()
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(NoopHandler).observationConvention(probe)
        val context = ToolLoopObservationContext(mockk<ToolLoopStartEvent>(), emptyList())

        assertThrows<IllegalStateException> {
            Observations.observeOrSkip(registry, { context }) {
                throw IllegalStateException("loop failed") // output never set
            }
        }

        // The span still stops on failure (the convention is re-read), but no output was produced.
        assertNull(probe.outputsSeen.first(), "no output at span start")
        assertNull(probe.outputsSeen.last(), "no output captured when the work throws")
    }
}
