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
package com.embabel.agent.shell.config

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ShellPropertiesTest {

    @Test
    fun `should have default line length of 140`() {
        // Arrange & Act
        val properties = ShellProperties()

        // Assert
        assertEquals(140, properties.lineLength)
    }

    @Test
    fun `should have default redirectLogToFile as false`() {
        // Arrange & Act
        val properties = ShellProperties()

        // Assert
        assertFalse(properties.redirectLogToFile)
    }

    @Test
    fun `should allow setting line length`() {
        // Arrange
        val properties = ShellProperties()

        // Act
        properties.lineLength = 200

        // Assert
        assertEquals(200, properties.lineLength)
    }

    @Test
    fun `should allow setting redirectLogToFile`() {
        // Arrange
        val properties = ShellProperties()

        // Act
        properties.redirectLogToFile = true

        // Assert
        assertTrue(properties.redirectLogToFile)
    }

    @Test
    fun `should bind properties from configuration`() {
        // Arrange
        val contextRunner = ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration::class.java)
            .withPropertyValues(
                "embabel.agent.shell.line-length=100",
                "embabel.agent.shell.redirect-log-to-file=true"
            )

        // Act & Assert
        contextRunner.run { context ->
            assertNotNull(context.getBean(ShellProperties::class.java))
            val properties = context.getBean(ShellProperties::class.java)
            assertEquals(100, properties.lineLength)
            assertTrue(properties.redirectLogToFile)
        }
    }

    @Test
    fun `should use default values when properties not configured`() {
        // Arrange
        val contextRunner = ApplicationContextRunner()
            .withUserConfiguration(TestConfiguration::class.java)

        // Act & Assert
        contextRunner.run { context ->
            val properties = context.getBean(ShellProperties::class.java)
            assertEquals(140, properties.lineLength)
            assertFalse(properties.redirectLogToFile)
        }
    }

    @EnableConfigurationProperties(ShellProperties::class)
    private class TestConfiguration
}
