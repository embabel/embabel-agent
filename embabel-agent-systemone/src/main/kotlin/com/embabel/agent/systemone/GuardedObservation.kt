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
 * telemetry never changes a call's result, and the registry's current scope is always put back.
 */
internal class GuardedObservation(
    private val registry: ObservationRegistry,
    name: String,
    vararg tags: Pair<String, String>,
) {
    private val observation: Observation? = guard("start") {
        val created = Observation.createNotStarted(name, registry)
        tags.forEach { (key, value) -> created.lowCardinalityKeyValue(key, value) }
        created.start()
    }

    /** Runs the work with this observation current. */
    fun <T> inScope(work: () -> T): T {
        val previous = registry.currentObservationScope
        val scope = observation?.let { guard("open scope") { it.openScope() } }
        try {
            return work()
        } finally {
            scope?.let { guard("close scope") { it.close() } }
            if (registry.currentObservationScope !== previous) {
                guard("restore scope") { registry.setCurrentObservationScope(previous) }
            }
        }
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
