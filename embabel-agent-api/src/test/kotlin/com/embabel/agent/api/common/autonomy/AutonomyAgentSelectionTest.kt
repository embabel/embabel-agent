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
package com.embabel.agent.api.common.autonomy

import com.embabel.agent.core.*
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.HasContent
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.spi.Ranking
import com.embabel.agent.spi.Rankings
import com.embabel.agent.testing.integration.FakeRanker
import com.embabel.agent.config.AgentPlatformProperties
import com.embabel.agent.testing.integration.forAutonomyTesting
import com.embabel.common.core.types.Described
import com.embabel.common.core.types.Named
import io.mockk.*
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*

/**
 * Tests for the agent selection functionality in Autonomy.
 *
 * This test suite focuses on the following aspects of agent selection:
 *
 * 1. Proper agent ranking and threshold-based filtering
 *    - Tests that agents are ranked using the provided Ranker
 *    - Verifies that only agents above the confidence threshold are selected
 *
 * 2. Agent execution after selection
 *    - Tests that the selected agent is executed through the platform
 *    - Verifies result handling based on process status
 *
 * Technical notes:
 * - We use a spy approach for Agent objects to prevent infoString errors
 * - The test creates real objects where possible, with minimal mocking
 * - A custom ranker returns varied scores to test threshold comparisons
 * - The tests validate that scores above threshold pass, while scores below fail
 * - ProcessOptions(test=false) is used to prevent RandomRanker substitution
 *
 * This approach ensures we test the actual agent selection behavior of the Autonomy
 * class while avoiding issues with infoString calls during Agent operations.
 */
class AutonomyAgentSelectionTest {

    @BeforeEach
    fun setUp() {
        clearAllMocks()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /**
     * Test agent selection with valid input (confidence above threshold).
     *
     * This test verifies that when an agent with confidence above threshold is found,
     * it is executed properly.
     *
     * Test approach:
     * 1. Setup test with an agent having score 0.8 (above threshold 0.5)
     * 2. Create a real Agent as a spy to handle infoString calls
     * 3. Mock only the necessary external dependencies
     * 4. Use a ranker that returns high scores above threshold
     * 5. Execute the real chooseAndRunAgent method (not mocked)
     * 6. Verify all key method interactions and event publications
     *
     * Critical aspects tested:
     * - Agent selection based on confidence score comparison to threshold
     * - Process execution with the selected agent
     * - Result handling based on process status
     * - Event publication for agent selection
     */
    @Test
    @DisplayName("test agent selection with valid input (score above threshold)")
    fun testChooseAndRunAgentWithValidInput() {
        val userInput = "Find a horoscope for Lynda who is a Scorpio"

        // Create a real goal for our agent
        val testGoal = Goal(
            name = "testGoal",
            description = "Test goal",
            value = 0.8
        )

        // Create a real agent as a spy
        val realAgent = spyk(
            Agent(
                name = "realTestAgent",
                provider = "test",
                description = "Real Test Agent",
                actions = emptyList(),
                goals = setOf(testGoal),
                conditions = emptySet(),
            )
        ) {
            // Only override the problematic infoString method
            every { infoString(any()) } returns "TestAgent() - spied"
        }

        val testProcess = mockk<AgentProcess>()
        val testOutput = object : HasContent {
            override val content = "Test output content"
        }

        every { testProcess.status } returns AgentProcessStatusCode.COMPLETED
        every { testProcess.lastResult() } returns testOutput

        // Mock platform to return available agents and execute our test agent
        val agentPlatform = mockk<AgentPlatform>()
        every { agentPlatform.agents() } returns listOf(realAgent)
        every {
            agentPlatform.createAgentProcess(
                processOptions = any(),
                agent = realAgent,
                bindings = any<Map<String, Any>>()
            )
        } returns testProcess
        every { testProcess.run() } returns testProcess

        // Create a ranker that returns high confidence for our test agent
        val highScoreRanker = object : FakeRanker {
            override fun <T> rank(
                description: String,
                userInput: String,
                rankables: Collection<T>,
            ): Rankings<T> where T : Named, T : Described {
                return Rankings(rankables.map { Ranking(it, 0.8) })
            }
        }

        val eventListener = mockk<AgenticEventListener>(relaxUnitFun = true)

        every {
            agentPlatform.platformServices.eventListener
        } returns eventListener

        // Create the Autonomy instance to test
        val autonomy = Autonomy(
            agentPlatform = agentPlatform,
            ranker = highScoreRanker,
            properties = forAutonomyTesting(agentConfidenceCutOff = 0.5),
        )

        // Execute the real method - no mocking of chooseAndRunAgent
        val result = autonomy.chooseAndRunAgent(
            intent = userInput,
            processOptions = ProcessOptions(test = false)
        )

        assertNotNull(result, "Result should not be null")
        assertEquals(testOutput, result.output, "Output should match expected")
        assertEquals(testProcess, result.agentProcess, "Process should match expected")

        verify {
            agentPlatform.agents()

            // Verify the agent was executed
            agentPlatform.createAgentProcess(
                processOptions = any(),
                agent = realAgent,
                bindings = any()
            )

            testProcess.status
            testProcess.lastResult()
        }

        verify {
            // Event listener should be called with events related to agent selection
            eventListener.onPlatformEvent(
                withArg { event ->
                    // This verifies that events about ranking/selection were published
                    assertTrue(
                        event.toString().contains("Ranking") ||
                                event.toString().contains("Agent"),
                        "Event should be related to agent ranking or selection"
                    )
                }
            )
        }
    }

    /**
     * Test agent selection with invalid input (confidence below threshold).
     *
     * This test verifies that when no agent has confidence above threshold,
     * a NoAgentFound exception is thrown with the appropriate details.
     *
     * Test approach:
     * 1. Setup test with an agent having score 0.3 (below threshold 0.5)
     * 2. Create a real Agent as a spy to handle infoString calls
     * 3. Use a ranker that returns low scores below threshold
     * 4. Execute the real chooseAndRunAgent method (not mocked)
     * 5. Verify the expected exception is thrown with correct details
     *
     * Critical aspects tested:
     * - Agent ranking and filtering based on confidence threshold
     * - Proper exception thrown when no agent meets the threshold
     * - Exception contains correct ranking information
     */
    @Test
    @DisplayName("test NoAgentFound is thrown when agent confidence score is below threshold")
    fun testChooseAndRunAgentWithInvalidInput() {
        // Setup test data
        val userInput = "Some input that won't match any agent"

        // Create a real agent but with low confidence
        val lowConfidenceAgent = spyk(
            Agent(
                name = "lowConfidenceAgent",
                description = "Agent with low confidence score",
                provider = "test",
                actions = emptyList(),
                goals = emptySet(),
                conditions = emptySet(),
            )
        ) {
            // Only override the problematic infoString method
            every { infoString(any()) } returns "LowConfidenceAgent() - spied"
        }

        // Mock platform to return our agent
        val agentPlatform = mockk<AgentPlatform>()
        every { agentPlatform.agents() } returns listOf(lowConfidenceAgent)


        // Create a ranker that returns low confidence scores (below threshold)
        val lowScoreRanker = object : FakeRanker {
            override fun <T> rank(
                description: String,
                userInput: String,
                rankables: Collection<T>,
            ): Rankings<T> where T : Named, T : Described {
                return Rankings(rankables.map { Ranking(it, 0.3) })
            }
        }

        // Create event listener
        val eventListener = mockk<AgenticEventListener>(relaxUnitFun = true)
        every {
            agentPlatform.platformServices.eventListener
        } returns eventListener

        // Create the Autonomy instance to test
        val autonomy = Autonomy(
            agentPlatform = agentPlatform,
            ranker = lowScoreRanker,
            properties = forAutonomyTesting(agentConfidenceCutOff = 0.5),
        )

        // Execute and verify exception is thrown
        val exception = assertThrows<NoAgentFound> {
            autonomy.chooseAndRunAgent(
                intent = userInput,
                processOptions = ProcessOptions(test = false)
            )
        }

        // Verify exception contains expected details
        assertTrue(exception.basis is UserInput, "Exception basis should be UserInput")
        assertEquals(
            userInput, (exception.basis as UserInput).content,
            "UserInput content should match"
        )

        // Verify rankings in exception
        val rankings = exception.agentRankings.rankings()
        assertEquals(1, rankings.size, "Should have exactly one ranking")

        val ranking = rankings.first()
        assertEquals(
            "lowConfidenceAgent", ranking.match.name,
            "Ranking should contain our low confidence agent"
        )
        assertEquals(
            0.3, ranking.score,
            "Ranking score should be 0.3, below threshold of 0.5"
        )

        // Verify platform interaction
        verify {
            agentPlatform.agents()
        }

        // Verify events
        verify {
            eventListener.onPlatformEvent(any())
        }
    }
}
