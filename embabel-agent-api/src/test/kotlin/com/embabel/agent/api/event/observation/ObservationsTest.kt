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

import io.micrometer.observation.GlobalObservationConvention
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationHandler
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Verifies the [Observations.observeOrSkip] contract: transparent passthrough of the work's
 * return value (including `null`), a span that always closes — errored when the work throws —
 * and span naming owned entirely by the registered convention (the helper passes only a neutral
 * placeholder, never a semantic name).
 */
class ObservationsTest {

    private class RecordingHandler : ObservationHandler<Observation.Context> {
        val stopped = CopyOnWriteArrayList<Observation.Context>()
        val errored = CopyOnWriteArrayList<Throwable>()
        override fun supportsContext(context: Observation.Context) = true
        override fun onStop(context: Observation.Context) {
            stopped += context
        }

        override fun onError(context: Observation.Context) {
            context.error?.let { errored += it }
        }
    }

    /** A global convention that names every span it supports, mimicking the embabel conventions. */
    private class NamingConvention(private val name: String) :
        GlobalObservationConvention<Observation.Context> {
        override fun supportsContext(context: Observation.Context) = true
        override fun getName() = name
    }

    private fun registryWith(handler: ObservationHandler<*>): ObservationRegistry {
        val registry = ObservationRegistry.create()
        registry.observationConfig().observationHandler(handler)
        return registry
    }

    @Test
    fun `NOOP registry runs work and returns its value without building the observation context`() {
        var contextBuilt = false
        val workResult = 42
        val result = Observations.observeOrSkip(
            ObservationRegistry.NOOP, { contextBuilt = true; Observation.Context() },
        ) { workResult }
        assertEquals(workResult, result)
        // The NOOP path must short-circuit before the context supplier runs: no observation, no
        // allocation. (A recording handler cannot be used here — a no-op registry has no handlers.)
        assertFalse(contextBuilt, "NOOP path must not build the observation context")
    }

    @Test
    fun `observed work returns its value and closes the span exactly once`() {
        val handler = RecordingHandler()
        val registry = registryWith(handler)
        val result = Observations.observeOrSkip(
            registry, { Observation.Context() },
        ) { "ok" }
        assertEquals("ok", result)
        assertEquals(1, handler.stopped.size, "span should have closed exactly once")
        assertTrue(handler.errored.isEmpty(), "successful work must not error the span")
    }

    @Test
    fun `nullable work returning null is returned as null, not an NPE`() {
        val registry = registryWith(RecordingHandler())
        val result: String? = Observations.observeOrSkip(
            registry, { Observation.Context() },
        ) { null }
        assertNull(result, "the helper must propagate a null result rather than throw")
    }

    @Test
    fun `work that throws propagates the original exception and errors the span`() {
        val handler = RecordingHandler()
        val registry = registryWith(handler)
        val boom = IllegalStateException("boom")
        val thrown = assertThrows<IllegalStateException> {
            Observations.observeOrSkip(
                registry, { Observation.Context() },
            ) { throw boom }
        }
        assertSame(boom, thrown, "the original exception must propagate unchanged")
        assertEquals(1, handler.stopped.size, "span must still close on failure (no scope leak)")
        assertTrue(handler.errored.contains(boom), "span must be marked errored with the thrown exception")
    }

    @Test
    fun `a registered convention name overrides the neutral placeholder`() {
        val handler = RecordingHandler()
        val registry = registryWith(handler)
        registry.observationConfig().observationConvention(NamingConvention("embabel.agent"))

        Observations.observeOrSkip(registry, { Observation.Context() }) { "x" }

        assertEquals(
            "embabel.agent",
            handler.stopped.single().name,
            "the span name must come from the registered convention, not the helper",
        )
    }

    @Test
    fun `without a matching convention the span falls back to the neutral placeholder`() {
        val handler = RecordingHandler()
        val registry = registryWith(handler)

        Observations.observeOrSkip(registry, { Observation.Context() }) { "x" }

        assertEquals(
            Observations.PLACEHOLDER_NAME,
            handler.stopped.single().name,
            "with no convention the helper's neutral placeholder is used",
        )
    }
}
