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
package com.embabel.agent

import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.ComponentScan
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for the annotation contract exposed by [AgentTestApplication].
 */
class AgentTestApplicationTest {

    private val expectedScanPackages = setOf(
        "com.embabel.agent.api",
        "com.embabel.agent.core",
        "com.embabel.agent.experimental",
        "com.embabel.agent.prompt",
        "com.embabel.agent.spi",
        "com.embabel.agent.test",
        "com.embabel.agent.tools",
        "com.embabel.agent.web",
        "com.embabel.example",
        "com.embabel.agent.shell",
        "com.embabel.agent.mcpserver",
    )

    @Test
    fun `AgentTestApplication declares expected Spring application annotations`() {
        // Arrange

        // Act
        val springBootApplication = AgentTestApplication::class.java.getAnnotation(SpringBootApplication::class.java)
        val configurationPropertiesScan = AgentTestApplication::class.java.getAnnotation(ConfigurationPropertiesScan::class.java)
        val componentScan = AgentTestApplication::class.java.getAnnotation(ComponentScan::class.java)

        // Assert
        assertNotNull(springBootApplication)
        assertNotNull(configurationPropertiesScan)
        assertNotNull(componentScan)
    }

    @Test
    fun `AgentTestApplication uses expected configuration properties scan packages`() {
        // Arrange
        val configurationPropertiesScan = AgentTestApplication::class.java.getAnnotation(ConfigurationPropertiesScan::class.java)

        // Act
        val packages = configurationPropertiesScan.basePackages.toSet()

        // Assert
        assertEquals(expectedScanPackages, packages)
    }

    @Test
    fun `AgentTestApplication uses expected component scan packages`() {
        // Arrange
        val componentScan = AgentTestApplication::class.java.getAnnotation(ComponentScan::class.java)

        // Act
        val packages = componentScan.basePackages.toSet()

        // Assert
        assertEquals(expectedScanPackages, packages)
    }
}
