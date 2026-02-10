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
package com.embabel.agent.api.common.autonomy

import com.embabel.agent.api.common.ranking.Ranking
import com.embabel.agent.api.common.ranking.Rankings
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.*
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.HasContent
import com.embabel.agent.test.integration.FakeRanker
import com.embabel.agent.test.integration.forAutonomyTesting
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import io.mockk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Tests for chooseAndAccomplishGoal with arbitrary bindings.
 *
 * Currently, createGoalSeeker requires a UserInput in bindings.
 * These tests demonstrate that chooseAndAccomplishGoal should support
 * arbitrary bindings like { task: "do something", person: Person }.
 */
class AutonomyArbitraryBindingsTest {

    data class Person(val name: String, val age: Int)

    data class Task(val description: String)

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Test that chooseAndAccomplishGoal works with arbitrary bindings
     * that don't include UserInput.
     *
     * This test currently FAILS because createGoalSeeker hard-codes
     * the requirement for UserInput in bindings:
     *
     * ```
     * val userInput = bindings.values.firstOrNull { it is UserInput } as? UserInput
     *     ?: throw IllegalArgumentException("No UserInput found in bindings: $bindings")
     * ```
     *
     * The goal is to support bindings like:
     * - { task: Task("do something"), person: Person("John", 30) }
     * - { intent: "some intent string" }
     * - Any other arbitrary bindings
     */
    @Test
    @DisplayName("chooseAndAccomplishGoal should work with arbitrary bindings without UserInput")
    fun testChooseAndAccomplishGoalWithArbitraryBindings() {
        val testGoal = Goal(
            name = "processPersonTask",
            description = "Process a task for a person",
            value = 0.8
        )

        val realAgent = spyk(
            Agent(
                name = "personTaskAgent",
                description = "Agent that processes tasks for people",
                provider = "test",
                actions = emptyList(),
                goals = setOf(testGoal),
            )
        ) {
            every { infoString(any()) } returns "PersonTaskAgent() - spied"
        }

        val agentScope = mockk<AgentScope>()
        every { agentScope.goals } returns setOf(testGoal)
        every { agentScope.createAgent(any(), any(), any()) } returns realAgent

        val testProcess = mockk<AgentProcess>()
        val testOutput = object : HasContent {
            override val content = "Task processed for person"
        }
        every { testProcess.status } returns AgentProcessStatusCode.COMPLETED
        every { testProcess.lastResult() } returns testOutput

        val agentPlatform = mockk<AgentPlatform>()
        every {
            agentPlatform.createAgentProcess(
                processOptions = any(),
                agent = any(),
                bindings = any<Map<String, Any>>()
            )
        } returns testProcess
        every { testProcess.run() } returns testProcess

        val testRanker = object : FakeRanker {
            override fun <T> rank(
                description: String,
                userInput: String,
                rankables: Collection<T>,
            ): Rankings<T> where T : Named, T : Described {
                return Rankings(rankables.map { Ranking(it, 0.9) })
            }
        }

        val eventListener = mockk<AgenticEventListener>(relaxUnitFun = true)
        every { agentPlatform.platformServices.eventListener } returns eventListener

        val autonomy = Autonomy(
            agentPlatform = agentPlatform,
            ranker = testRanker,
            properties = forAutonomyTesting(
                goalConfidenceCutOff = 0.5,
                agentConfidenceCutOff = 0.5
            ),
        )

        // Arbitrary bindings without UserInput
        val arbitraryBindings = mapOf(
            "task" to Task("do something important"),
            "person" to Person("Alice", 28)
        )

        // This should work but currently throws IllegalArgumentException
        // because createGoalSeeker requires UserInput
        val result = autonomy.chooseAndAccomplishGoal(
            processOptions = ProcessOptions(),
            goalChoiceApprover = GoalChoiceApprover.APPROVE_ALL,
            agentScope = agentScope,
            bindings = arbitraryBindings
        )

        assertNotNull(result, "Result should not be null")
        assertEquals(testOutput, result.output, "Output should match expected")
    }

    /**
     * Test that chooseAndAccomplishGoal works with bindings containing
     * a string intent instead of UserInput.
     *
     * This is a common pattern where the user provides an intent string
     * directly rather than wrapping it in UserInput.
     */
    @Test
    @DisplayName("chooseAndAccomplishGoal should work with string intent binding")
    fun testChooseAndAccomplishGoalWithStringIntent() {
        val testGoal = Goal(
            name = "handleIntent",
            description = "Handle user intent",
            value = 0.8
        )

        val realAgent = spyk(
            Agent(
                name = "intentAgent",
                description = "Agent that handles intents",
                provider = "test",
                actions = emptyList(),
                goals = setOf(testGoal),
            )
        ) {
            every { infoString(any()) } returns "IntentAgent() - spied"
        }

        val agentScope = mockk<AgentScope>()
        every { agentScope.goals } returns setOf(testGoal)
        every { agentScope.createAgent(any(), any(), any()) } returns realAgent

        val testProcess = mockk<AgentProcess>()
        val testOutput = object : HasContent {
            override val content = "Intent handled"
        }
        every { testProcess.status } returns AgentProcessStatusCode.COMPLETED
        every { testProcess.lastResult() } returns testOutput

        val agentPlatform = mockk<AgentPlatform>()
        every {
            agentPlatform.createAgentProcess(
                processOptions = any(),
                agent = any(),
                bindings = any<Map<String, Any>>()
            )
        } returns testProcess
        every { testProcess.run() } returns testProcess

        val testRanker = object : FakeRanker {
            override fun <T> rank(
                description: String,
                userInput: String,
                rankables: Collection<T>,
            ): Rankings<T> where T : Named, T : Described {
                return Rankings(rankables.map { Ranking(it, 0.9) })
            }
        }

        val eventListener = mockk<AgenticEventListener>(relaxUnitFun = true)
        every { agentPlatform.platformServices.eventListener } returns eventListener

        val autonomy = Autonomy(
            agentPlatform = agentPlatform,
            ranker = testRanker,
            properties = forAutonomyTesting(
                goalConfidenceCutOff = 0.5,
                agentConfidenceCutOff = 0.5
            ),
        )

        // Binding with intent as string
        val intentBindings = mapOf(
            "intent" to "process this request",
            "context" to mapOf("priority" to "high")
        )

        // This should work but currently throws IllegalArgumentException
        val result = autonomy.chooseAndAccomplishGoal(
            processOptions = ProcessOptions(),
            goalChoiceApprover = GoalChoiceApprover.APPROVE_ALL,
            agentScope = agentScope,
            bindings = intentBindings
        )

        assertNotNull(result, "Result should not be null")
        assertEquals(testOutput, result.output, "Output should match expected")
    }

    /**
     * Verify backward compatibility: chooseAndAccomplishGoal should
     * continue to work when UserInput is in bindings.
     */
    @Test
    @DisplayName("chooseAndAccomplishGoal should maintain backward compatibility with UserInput")
    fun testBackwardCompatibilityWithUserInput() {
        val testGoal = Goal(
            name = "testGoal",
            description = "Test goal with UserInput",
            value = 0.8
        )

        val realAgent = spyk(
            Agent(
                name = "testAgent",
                description = "Test agent",
                provider = "test",
                actions = emptyList(),
                goals = setOf(testGoal),
            )
        ) {
            every { infoString(any()) } returns "TestAgent() - spied"
        }

        val agentScope = mockk<AgentScope>()
        every { agentScope.goals } returns setOf(testGoal)
        every { agentScope.createAgent(any(), any(), any()) } returns realAgent

        val testProcess = mockk<AgentProcess>()
        val testOutput = object : HasContent {
            override val content = "Test output"
        }
        every { testProcess.status } returns AgentProcessStatusCode.COMPLETED
        every { testProcess.lastResult() } returns testOutput

        val agentPlatform = mockk<AgentPlatform>()
        every {
            agentPlatform.createAgentProcess(
                processOptions = any(),
                agent = any(),
                bindings = any<Map<String, Any>>()
            )
        } returns testProcess
        every { testProcess.run() } returns testProcess

        val testRanker = object : FakeRanker {
            override fun <T> rank(
                description: String,
                userInput: String,
                rankables: Collection<T>,
            ): Rankings<T> where T : Named, T : Described {
                return Rankings(rankables.map { Ranking(it, 0.9) })
            }
        }

        val eventListener = mockk<AgenticEventListener>(relaxUnitFun = true)
        every { agentPlatform.platformServices.eventListener } returns eventListener

        val autonomy = Autonomy(
            agentPlatform = agentPlatform,
            ranker = testRanker,
            properties = forAutonomyTesting(
                goalConfidenceCutOff = 0.5,
                agentConfidenceCutOff = 0.5
            ),
        )

        // Traditional bindings with UserInput - this should continue to work
        val userInputBindings = mapOf(
            IoBinding.DEFAULT_BINDING to UserInput("Find horoscope for Alice")
        )

        val result = autonomy.chooseAndAccomplishGoal(
            processOptions = ProcessOptions(),
            goalChoiceApprover = GoalChoiceApprover.APPROVE_ALL,
            agentScope = agentScope,
            bindings = userInputBindings
        )

        assertNotNull(result, "Result should not be null")
        assertEquals(testOutput, result.output, "Output should match expected")
    }

    /**
     * Test mixed bindings: UserInput plus additional arbitrary bindings.
     * This should work to maintain backward compatibility while allowing
     * additional context.
     */
    @Test
    @DisplayName("chooseAndAccomplishGoal should work with UserInput plus additional bindings")
    fun testMixedBindingsWithUserInputAndArbitrary() {
        val testGoal = Goal(
            name = "processWithContext",
            description = "Process with additional context",
            value = 0.8
        )

        val realAgent = spyk(
            Agent(
                name = "contextAgent",
                description = "Agent with context",
                provider = "test",
                actions = emptyList(),
                goals = setOf(testGoal),
            )
        ) {
            every { infoString(any()) } returns "ContextAgent() - spied"
        }

        val agentScope = mockk<AgentScope>()
        every { agentScope.goals } returns setOf(testGoal)
        every { agentScope.createAgent(any(), any(), any()) } returns realAgent

        val testProcess = mockk<AgentProcess>()
        val testOutput = object : HasContent {
            override val content = "Processed with context"
        }
        every { testProcess.status } returns AgentProcessStatusCode.COMPLETED
        every { testProcess.lastResult() } returns testOutput

        val agentPlatform = mockk<AgentPlatform>()
        every {
            agentPlatform.createAgentProcess(
                processOptions = any(),
                agent = any(),
                bindings = any<Map<String, Any>>()
            )
        } returns testProcess
        every { testProcess.run() } returns testProcess

        val testRanker = object : FakeRanker {
            override fun <T> rank(
                description: String,
                userInput: String,
                rankables: Collection<T>,
            ): Rankings<T> where T : Named, T : Described {
                return Rankings(rankables.map { Ranking(it, 0.9) })
            }
        }

        val eventListener = mockk<AgenticEventListener>(relaxUnitFun = true)
        every { agentPlatform.platformServices.eventListener } returns eventListener

        val autonomy = Autonomy(
            agentPlatform = agentPlatform,
            ranker = testRanker,
            properties = forAutonomyTesting(
                goalConfidenceCutOff = 0.5,
                agentConfidenceCutOff = 0.5
            ),
        )

        // Mixed bindings: UserInput plus additional context
        val mixedBindings = mapOf(
            IoBinding.DEFAULT_BINDING to UserInput("Process request"),
            "person" to Person("Bob", 35),
            "priority" to "high"
        )

        val result = autonomy.chooseAndAccomplishGoal(
            processOptions = ProcessOptions(),
            goalChoiceApprover = GoalChoiceApprover.APPROVE_ALL,
            agentScope = agentScope,
            bindings = mixedBindings
        )

        assertNotNull(result, "Result should not be null")
        assertEquals(testOutput, result.output, "Output should match expected")
    }
}
