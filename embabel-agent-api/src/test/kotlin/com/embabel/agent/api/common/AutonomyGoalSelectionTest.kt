/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
package com.embabel.agent.api.common

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.common.autonomy.AutonomyProperties
import com.embabel.agent.api.common.autonomy.GoalChoiceApprover
import com.embabel.agent.api.common.autonomy.NoGoalFound
import com.embabel.agent.core.*
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.HasContent
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.spi.Ranking
import com.embabel.agent.spi.Rankings
import com.embabel.agent.testing.FakeRanker
import io.mockk.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for the goal selection functionality in Autonomy.
 *
 * This test suite focuses on testing the chooseAndAccomplishGoal method with:
 *
 * 1. Valid Input: High confidence goal (above threshold)
 *    - Tests that goals with confidence above threshold are selected
 *    - Verifies agent creation and execution works properly
 *
 * 2. Invalid Input: Low confidence goal (below threshold)
 *    - Tests that goals with confidence below threshold trigger NoGoalFound
 *    - Verifies exception contains expected goal rankings
 *
 * Technical notes:
 * - We use a spy approach for Agent objects to prevent infoString errors
 * - The tests ensure score comparison logic is properly exercised
 * - ProcessOptions(test=false) is used to prevent RandomRanker substitution
 * - Both tests validate core functionality without mocking the main method
 */
class AutonomyGoalSelectionTest {

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Test the valid input case for goal selection.
     *
     * This verifies that when a goal with confidence above threshold is found,
     * it is selected and executed properly.
     *
     * Test approach:
     * 1. Setup test with a goal having score 0.8 (above threshold 0.5)
     * 2. Create a real Agent as a spy to handle infoString calls
     * 3. Mock only the necessary external dependencies
     * 4. Use a ranker that returns varied scores (0.8 for test goal, 0.3 for others)
     * 5. Execute the real chooseAndAccomplishGoal method (not mocked)
     * 6. Verify all key method interactions and event publications
     *
     * Critical aspects tested:
     * - Goal selection based on confidence score comparison to threshold
     * - Agent creation with the selected goal
     * - Process execution and result handling
     * - Event publication for goal/agent selection
     *
     * The key testing technique here is using a spy on a real Agent object
     * and overriding only the infoString method that causes issues.
     * This allows us to test the real pruning logic while avoiding
     * the MockKException for infoString.
     */
    @Test
    @DisplayName("test goal selection with valid input (score above threshold)")
    fun testChooseAndAccomplishGoalWithValidInput() {
        // Setup test data
        val userInput = "Find a horoscope for Lynda who is a Scorpio"

        // Create a real goal object
        val testGoal = Goal(
            name = "testGoal",
            description = "Test goal with high confidence",
            value = 0.8
        )

        // Create a real agent as a spy - this is the KEY to fixing the infoString issue
        val realAgent = spyk(
            Agent(
                name = "realAgent",
                description = "Real Agent",
                provider = "test",
                actions = emptyList(),
                goals = setOf(testGoal),
            )
        ) {
            // Only override the problematic infoString method
            every { infoString(any()) } returns "TestAgent() - spied"
        }

        // Mock the scope
        val agentScope = mockk<AgentScope>()
        every { agentScope.goals } returns setOf(testGoal)
        every { agentScope.createAgent(any(), any(), any()) } returns realAgent

        // Mock process with expected return value
        val testProcess = mockk<AgentProcess>()
        val testOutput = object : HasContent {
            override val content = "Test output content"
        }

        // Configure process to return COMPLETED status
        // This tests that DynamicExecutionResult.fromProcessStatus correctly handles the status
        every { testProcess.status } returns AgentProcessStatusCode.COMPLETED

        // Configure the result to be returned
        every { testProcess.lastResult() } returns testOutput

        // Mock platform to return our test process
        val agentPlatform = mockk<AgentPlatform>()
        every {
            agentPlatform.runAgentFrom(
                processOptions = any(),
                agent = any(),
                bindings = any<Map<String, Any>>()
            )
        } returns testProcess

        // Create a more realistic ranker that returns varied confidence scores
        // This better tests the score comparison logic
        val testRanker = object : FakeRanker {
            override fun <T> rank(
                description: String,
                userInput: String,
                rankables: Collection<T>
            ): Rankings<T> where T : com.embabel.common.core.types.Named, T : com.embabel.common.core.types.Described {
                // Create a map of rankings with different scores
                val rankings = rankables.mapIndexed { index, item ->
                    val score = when (item.name) {
                        "testGoal" -> 0.8  // High score for our test goal (above threshold)
                        else -> 0.3        // Low score for any other goals (below threshold)
                    }
                    Ranking(item, score)
                }
                return Rankings(rankings)
            }
        }

        // Create event listener mock to verify event publication
        val eventListener = mockk<AgenticEventListener>(relaxUnitFun = true)

        every {
            agentPlatform.platformServices.eventListener
        } returns eventListener

        // Create the Autonomy instance to test with different thresholds
        // to verify the comparison logic is working
        val autonomy = Autonomy(
            agentPlatform = agentPlatform,
            ranker = testRanker,
            properties = AutonomyProperties(
                goalConfidenceCutOff = 0.5,  // Our test goal (0.8) should be above this
                agentConfidenceCutOff = 0.5
            ),
        )

        // Execute the real method - no mocking of chooseAndAccomplishGoal
        val result = autonomy.chooseAndAccomplishGoal(
            intent = userInput,
            processOptions = ProcessOptions(test = false),
            goalChoiceApprover = GoalChoiceApprover.APPROVE_ALL,
            agentScope = agentScope
        )

        // Verify the result
        assertNotNull(result, "Result should not be null")
        assertEquals(testOutput, result.output, "Output should match expected")
        assertEquals(testProcess, result.agentProcess, "Process should match expected")

        // Verify the key method interactions
        verify {
            // Verify the scope was accessed to get goals
            agentScope.goals

            // Verify agent creation
            agentScope.createAgent(any(), any(), any())

            // Verify goal selection
            realAgent.withSingleGoal(testGoal)

            // Verify that the chosen agent was executed
            agentPlatform.runAgentFrom(
                processOptions = any(),
                agent = any(),
                bindings = any()
            )

            // Verify process status was checked
            testProcess.status

            // Verify result was retrieved
            testProcess.lastResult()
        }

        // Verify events for goal selection - this confirms the ranking logic was used
        verify {
            // Event listener should be called with events related to goal selection
            eventListener.onPlatformEvent(
                withArg { event ->
                    // This verifies that events about ranking/selection were published
                    assertTrue(
                        event.toString().contains("Ranking") ||
                                event.toString().contains("Goal") ||
                                event.toString().contains("Agent"),
                        "Event should be related to goal/agent ranking or selection"
                    )
                }
            )
        }
    }

    /**
     * Test for NoGoalFound exception being thrown when goal confidence is too low.
     *
     * This test verifies that when no goal has confidence above threshold,
     * a NoGoalFound exception is thrown with the appropriate details.
     *
     * Test approach:
     * 1. Setup test with a goal having score 0.3 (below threshold 0.5)
     * 2. Use a FakeRanker that returns low confidence scores
     * 3. Execute the real chooseAndAccomplishGoal method (not mocked)
     * 4. Verify the expected exception is thrown with correct details
     *
     * Critical aspects tested:
     * - Goal ranking and filtering based on confidence threshold
     * - Proper exception thrown when no goal meets the threshold
     * - Exception contains correct ranking information
     *
     * The test explicitly uses ProcessOptions(test=false) to prevent
     * the random ranker substitution that occurs in test mode.
     */
    @Test
    @DisplayName("test NoGoalFound is thrown when goal confidence score is below threshold")
    fun testChooseAndAccomplishGoalWithInvalidInput() {
        // Create test objects
        val testGoal = Goal(
            name = "testGoal",
            description = "Test Goal",
            value = 0.2
        )

        // Create a real agent as a spy - this is the KEY to fixing the infoString issue
        val agent = spyk(
            Agent(
                name = "testAgent",
                provider = "test",
                description = "Test agent",
                actions = emptyList(),
                goals = setOf(testGoal),
                conditions = emptySet(),
            )
        ) {
            // Only override the problematic infoString method
            every { infoString(any()) } returns "TestAgent() - spied"
        }

        // Create a ranker that returns low confidence scores
        val lowScoreRanker = object : FakeRanker {
            override fun <T> rank(
                description: String,
                userInput: String,
                rankables: Collection<T>
            ): Rankings<T> where T : com.embabel.common.core.types.Named, T : com.embabel.common.core.types.Described {
                // Return 0.3 which is below the 0.5 threshold
                return Rankings(rankables.map { Ranking(it, 0.3) })
            }
        }

        // Create more specific mocks with proper enum handling
        val agentScope = mockk<AgentScope>(relaxUnitFun = true)
        val agentPlatform = mockk<AgentPlatform>(relaxUnitFun = true)
        val eventListener = mockk<AgenticEventListener>(relaxUnitFun = true)

        // Only mock the methods that are definitely needed
        every { agentScope.goals } returns setOf(testGoal)
        justRun { eventListener.onPlatformEvent(any()) }

        // Mock agent creation
        every {
            agentScope.createAgent(name = any(), provider = any(), description = any())
        } returns agent

        // Mock process with all necessary methods
        val mockProcess = mockk<AgentProcess>()
        every { mockProcess.status } returns AgentProcessStatusCode.COMPLETED
        every { mockProcess.id } returns "test-process-id"

        // Create a test output to return from lastResult
        val testOutput = object : HasContent {
            override val content = "Test output"
        }
        every { mockProcess.lastResult() } returns testOutput

        // Mock runAgentFrom
        every {
            agentPlatform.runAgentFrom(any(), any(), any())
        } returns mockProcess
        every {
            agentPlatform.platformServices.eventListener
        } returns eventListener

        // Create autonomy instance
        val autonomy = Autonomy(
            agentPlatform = agentPlatform,
            ranker = lowScoreRanker,
            properties = AutonomyProperties(goalConfidenceCutOff = 0.5),
        )

        // Execute and verify the exception is thrown
        val exception = assertThrows<NoGoalFound> {
            // IMPORTANT: Use test=false to prevent RandomRanker creation
            autonomy.chooseAndAccomplishGoal(
                intent = "test input",
                processOptions = ProcessOptions(test = false),
                goalChoiceApprover = GoalChoiceApprover.APPROVE_ALL,
                agentScope = agentScope
            )
        }

        // Verify the exception has the expected properties
        assertNotNull(exception.goalRankings, "Exception should have goalRankings")

        assertTrue(exception.basis is UserInput, "Exception basis should be UserInput")
        assertEquals(
            "test input", (exception.basis as UserInput).content,
            "UserInput should contain our test string"
        )

        // Verify rankings
        assertEquals(
            1, exception.goalRankings.rankings.size,
            "There should be exactly one ranking"
        )

        val ranking = exception.goalRankings.rankings.firstOrNull()
        assertNotNull(ranking, "There should be a ranking in the exception")

        assertEquals(
            "testGoal", ranking?.match?.name,
            "Ranking should contain our goal"
        )
        assertEquals(
            0.3, ranking?.score,
            "Ranking score should be 0.3, below threshold"
        )
    }
}
