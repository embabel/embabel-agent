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
package com.embabel.agent.spi.config.spring

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Properties

/**
 * Guards the property files that [AgentPlatformPropertiesLoader] contributes through
 * `@PropertySource`. They are main resources, so every key declared there is imposed on every
 * consuming application. A key that Spring Boot has removed or deprecated at error level is
 * therefore not merely dead here — it silently stops doing whatever consumers relied on it for.
 */
internal class ShippedApplicationPropertiesTest {

    /**
     * Keys Spring Boot 4 no longer binds, mapped to their replacement.
     */
    private val unsupportedKeys = mapOf(
        "management.tracing.enabled" to "management.tracing.export.enabled",
    )

    @Test
    fun `shipped properties declare no key that Spring Boot 4 no longer binds`() {
        for (resource in listOf("agent-application.properties", "agent-platform.properties")) {
            val properties = load(resource)
            for ((unsupported, replacement) in unsupportedKeys) {
                assertTrue(
                    !properties.containsKey(unsupported),
                    "$resource declares '$unsupported', which Spring Boot 4 no longer binds. Use '$replacement'.",
                )
            }
        }
    }

    /**
     * Spring Boot samples 10% of traces by default. Now that the sampler is actually applied to the
     * tracer provider, leaving that default in place would silently discard nine agent traces out
     * of ten.
     */
    @Test
    fun `shipped properties keep full trace sampling`() {
        assertEquals(
            "1.0",
            load("agent-application.properties")["management.tracing.sampling.probability"],
        )
    }

    private fun load(resource: String): Properties {
        val properties = Properties()
        javaClass.classLoader.getResourceAsStream(resource).use {
            checkNotNull(it) { "$resource not found on the classpath" }.let(properties::load)
        }
        return properties
    }
}
