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
package com.embabel.agent.api.annotation.support.state

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.State
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

/**
 * Tests for @State class validation rules.
 *
 * State classes must be either:
 * - Static nested classes (Java records are implicitly static)
 * - Top-level classes
 *
 * Non-static inner classes are NOT allowed because they hold a reference
 * to their enclosing instance, causing serialization/persistence issues.
 */
class StateClassValidationTest {

    private val reader = AgentMetadataReader()

    @Nested
    inner class NonStaticInnerClassRejection {

        @Test
        fun `non-static inner state class is rejected`() {
            val exception = assertThrows(IllegalStateException::class.java) {
                reader.createAgentMetadata(AgentWithNonStaticInnerState())
            }
            assertTrue(
                exception.message?.contains("non-static inner class") == true,
                "Exception should mention non-static inner class: ${exception.message}"
            )
            assertTrue(
                exception.message?.contains("NonStaticState") == true,
                "Exception should mention the state class name: ${exception.message}"
            )
        }
    }

    @Nested
    inner class TopLevelStateClasses {

        @Test
        fun `top-level state class works correctly`() {
            val agent = reader.createAgentMetadata(AgentWithTopLevelState()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                mapOf("it" to TopLevelInput("hello")),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")
            val output = process.getValue("it", TopLevelOutput::class.java.name) as? TopLevelOutput
            assertNotNull(output, "Should have output")
            assertEquals("hello-processed-in-top-level-state", output!!.result)
        }

        @Test
        fun `multiple top-level states can transition between each other`() {
            val agent = reader.createAgentMetadata(AgentWithMultipleTopLevelStates()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                mapOf("it" to TopLevelInput("world")),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")
            val output = process.getValue("it", TopLevelOutput::class.java.name) as? TopLevelOutput
            assertNotNull(output, "Should have output")
            assertEquals("world-first-second-done", output!!.result)
        }
    }

    @Nested
    inner class StaticNestedStateClasses {

        @Test
        fun `static nested state class (data class) works correctly`() {
            val agent = reader.createAgentMetadata(AgentWithStaticNestedState()) as CoreAgent
            val ap = IntegrationTestUtils.dummyAgentPlatform()
            val process = ap.runAgentFrom(
                agent,
                ProcessOptions(),
                mapOf("it" to TopLevelInput("test")),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")
            val output = process.getValue("it", TopLevelOutput::class.java.name) as? TopLevelOutput
            assertNotNull(output, "Should have output")
            assertEquals("test-processed-in-nested-state", output!!.result)
        }
    }
}

// Test input/output types
data class TopLevelInput(val content: String)
data class TopLevelOutput(val result: String)

// ============================================================================
// Top-level state classes (RECOMMENDED for Kotlin)
// ============================================================================

/**
 * A top-level state class - this is the recommended pattern for Kotlin.
 */
@State
data class FirstTopLevelState(val data: String) {
    @AchievesGoal(description = "Processed in top-level state")
    @Action
    fun process(): TopLevelOutput = TopLevelOutput("$data-processed-in-top-level-state")
}

/**
 * Another top-level state for demonstrating state transitions.
 */
@State
data class SecondTopLevelState(val data: String) {
    @Action
    fun process(): ThirdTopLevelState = ThirdTopLevelState("$data-second")
}

@State
data class ThirdTopLevelState(val data: String) {
    @AchievesGoal(description = "Final state reached")
    @Action
    fun finish(): TopLevelOutput = TopLevelOutput("$data-done")
}

// ============================================================================
// Agents using top-level states
// ============================================================================

@Agent(description = "Agent using a top-level state class")
class AgentWithTopLevelState {
    @Action
    fun start(input: TopLevelInput): FirstTopLevelState = FirstTopLevelState(input.content)
}

@Agent(description = "Agent using multiple top-level state classes")
class AgentWithMultipleTopLevelStates {
    @Action
    fun start(input: TopLevelInput): SecondTopLevelState = SecondTopLevelState("${input.content}-first")
}

// ============================================================================
// Agent with static nested state (data class in Kotlin is static by default when nested)
// ============================================================================

@Agent(description = "Agent with static nested state")
class AgentWithStaticNestedState {

    @Action
    fun start(input: TopLevelInput): NestedState = NestedState(input.content)

    // In Kotlin, data classes nested inside a class are NOT automatically static.
    // They need to be in a companion object or be top-level.
    // However, this specific pattern works because Kotlin data classes
    // when declared at class level (not in a function) are considered static-like
    // for the purposes of Java reflection's Modifier.isStatic check.
    @State
    data class NestedState(val data: String) {
        @AchievesGoal(description = "Processed in nested state")
        @Action
        fun process(): TopLevelOutput = TopLevelOutput("$data-processed-in-nested-state")
    }
}

// ============================================================================
// Agent with non-static inner state (THIS SHOULD BE REJECTED)
// ============================================================================

@Agent(description = "Agent with non-static inner state - should be rejected")
class AgentWithNonStaticInnerState {

    @Action
    fun start(input: TopLevelInput): NonStaticState = NonStaticState(input.content)

    // This is a non-static inner class - it will be rejected
    // In Kotlin, regular (non-data) classes nested inside a class are inner by default
    @State
    inner class NonStaticState(val data: String) {
        @AchievesGoal(description = "Should not reach here")
        @Action
        fun process(): TopLevelOutput = TopLevelOutput("$data-should-not-work")
    }
}
