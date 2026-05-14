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
package com.embabel.agent.spi.support.guardrails

import com.embabel.agent.api.validation.guardrails.GuardRailInstantiationException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Tests for fail-on-error behavior in GlobalGuardRailsRegistry.
 * Tests the configuration property: embabel.agent.guardrails.fail-on-error
 */
class GlobalGuardRailsRegistryFailOnErrorTest {

    @Test
    fun `should throw exception when failOnError is true and class does not exist`() {
        val registry = GlobalGuardRailsRegistry(
            userInputClassNames = "com.example.NonExistentClass",
            assistantMessageClassNames = "",
            failOnError = true
        )

        val exception = assertThrows<GuardRailInstantiationException> {
            registry.init()
        }

        assertTrue(exception.message?.contains("NonExistentClass") == true,
            "Exception message should contain class name")
    }

    @Test
    fun `should throw exception when failOnError is true and class does not implement interface`() {
        val registry = GlobalGuardRailsRegistry(
            userInputClassNames = "java.lang.String",
            assistantMessageClassNames = "",
            failOnError = true
        )

        val exception = assertThrows<GuardRailInstantiationException> {
            registry.init()
        }

        assertTrue(exception.message?.contains("does not implement") == true,
            "Exception message should indicate interface mismatch")
    }

    @Test
    fun `should not throw exception when failOnError is false and class does not exist`() {
        val registry = GlobalGuardRailsRegistry(
            userInputClassNames = "com.example.NonExistentClass",
            assistantMessageClassNames = "",
            failOnError = false
        )

        assertDoesNotThrow {
            registry.init()
        }

        // Should have empty list since class failed to load
        assertTrue(GlobalGuardRailsRegistry.getUserInputGuardRails().isEmpty())
    }

    @Test
    fun `should not throw exception when failOnError is false and class does not implement interface`() {
        val registry = GlobalGuardRailsRegistry(
            userInputClassNames = "java.lang.String",
            assistantMessageClassNames = "",
            failOnError = false
        )

        assertDoesNotThrow {
            registry.init()
        }

        // Should have empty list since class failed validation
        assertTrue(GlobalGuardRailsRegistry.getUserInputGuardRails().isEmpty())
    }

    @Test
    fun `should load valid guardrails even when failOnError is true`() {
        val registry = GlobalGuardRailsRegistry(
            userInputClassNames = "com.embabel.agent.spi.support.guardrails.GlobalUserGuardRail",
            assistantMessageClassNames = "",
            failOnError = true
        )

        assertDoesNotThrow {
            registry.init()
        }

        assertEquals(1, GlobalGuardRailsRegistry.getUserInputGuardRails().size)
    }
}
