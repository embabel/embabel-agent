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
package com.embabel.agent.systemone

import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory

/**
 * One call's observation. A handler that throws is logged at DEBUG and otherwise ignored, so
 * telemetry never changes a call's result. When a handler fails part way through starting or
 * opening a scope, the observation is stopped or the scope closed, so the handlers before it can
 * clean up, and the registry's current scope is always put back.
 */
internal class GuardedObservation(
    private val registry: ObservationRegistry,
    name: String,
    vararg tags: Pair<String, String>,
) {
    private val observation: Observation? = start(name, tags)

    /** Runs the work with this observation current. */
    fun <T> inScope(work: () -> T): T {
        val previous = registry.currentObservationScope
        val scope = observation?.let { current -> guard("open scope") { current.openScope() } ?: closePartial(current, previous) }
        try {
            return work()
        } finally {
            scope?.let { guard("close scope") { it.close() } }
            if (registry.currentObservationScope !== previous) {
                guard("restore scope") { registry.setCurrentObservationScope(previous) }
            }
        }
    }

    /**
     * Reports why the call failed, so tracing handlers can mark the span. Only pass exceptions
     * that are safe to record, since handlers may export their message.
     *
     * @param failure what went wrong
     */
    fun error(failure: Throwable) {
        observation?.let { current -> guard("error") { current.error(failure) } }
    }

    /** Adds the closing tags and stops the observation. */
    fun stop(vararg tags: Pair<String, String>) {
        observation?.let { current ->
            guard("stop") {
                tags.forEach { (key, value) -> current.lowCardinalityKeyValue(key, value) }
                current.stop()
            }
        }
    }

    /**
     * Creates and starts the observation. When a handler fails while starting, it is stopped again.
     *
     * @param name the observation name
     * @param tags the low cardinality tags to start with
     * @return the started observation, or null when it could not be created or started
     */
    private fun start(name: String, tags: Array<out Pair<String, String>>): Observation? {
        val created = guard("create") {
            Observation.createNotStarted(name, registry).also { observation ->
                tags.forEach { (key, value) -> observation.lowCardinalityKeyValue(key, value) }
            }
        } ?: return null
        return guard("start") { created.start() } ?: run {
            guard("stop") { created.stop() }
            null
        }
    }

    /**
     * Closes a scope that a failing handler left half open, so earlier handlers can clean up.
     *
     * @param current this call's observation
     * @param previous the scope that was current before this call
     * @return null, since there is no scope to keep
     */
    private fun closePartial(current: Observation, previous: Observation.Scope?): Observation.Scope? {
        val partial = registry.currentObservationScope
        if (partial != null && partial !== previous && partial.currentObservation === current) {
            guard("close scope") { partial.close() }
        }
        return null
    }

    /**
     * Runs one handler phase. A failure is logged at DEBUG and not thrown.
     *
     * @param phase a name for the phase, for the log line
     * @param block the work to run
     * @return the block's result, or null when it failed
     */
    private inline fun <T> guard(phase: String, block: () -> T): T? =
        try {
            block()
        } catch (_: RuntimeException) {
            logger.debug("System One observation handler failed: phase={}", phase)
            null
        }

    private companion object {
        val logger = LoggerFactory.getLogger(GuardedObservation::class.java)
    }
}
