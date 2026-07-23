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
package com.embabel.agent.api.hitl

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.hitl.AbstractAwaitable
import com.embabel.agent.core.hitl.AwaitableResponse
import com.embabel.agent.core.hitl.ResponseImpact
import com.embabel.agent.core.hitl.waitFor
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.spi.support.InMemoryAgentProcessRepository
import com.embabel.agent.test.integration.IntegrationTestUtils
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.*

// ========== Domain Models ==========

// ========== Awaitable Implementation ==========

// ========== Agent Implementation ==========

// ========== REST DTOs ==========

// ========== Controller ==========

// ========== Test Configuration ==========

// ========== MockMVC Integration Test ==========

/**
 * Request payload shown to user
 */
data class ChoiceRequest(
    val prompt: String,
    val options: List<String>
)

/**
 * Domain object that will be in blackboard and passed to next action
 * This is the awaitable's payload type!
 */
data class UserChoice(
    val value: String,
    val request: ChoiceRequest? = null  // Optional: keep request context
)

/**
 * Final result of the adventure
 */
data class AdventureResult(val outcome: String)

/**
 * Custom Awaitable that handles user choice
 * Payload is UserChoice (what next action needs)
 */
class ChoiceAwaitable(
    val choiceRequest: ChoiceRequest
) : AbstractAwaitable<UserChoice, UserChoiceResponse>(
    UserChoice("", choiceRequest)  // Placeholder payload
) {

    override fun onResponse(
        response: UserChoiceResponse,
        agentProcess: AgentProcess
    ): ResponseImpact {
        // Transform HTTP response to domain object and add to blackboard
        agentProcess.blackboard.addObject(UserChoice(response.choice, choiceRequest))
        return ResponseImpact.UPDATED
    }
}

/**
 * HTTP response from user implementing AwaitableResponse
 */
data class UserChoiceResponse(
    override val id: String = UUID.randomUUID().toString(),
    override val awaitableId: String,
    val choice: String,
    override val timestamp: Instant = Instant.now()
) : AwaitableResponse {
    override fun persistent(): Boolean = false
}

/**
 * Adventure agent demonstrating the action chaining pattern with [waitFor].
 *
 * This agent has two actions that chain together:
 * - [getChoice] - Declares return type UserChoice, but actually throws [AwaitableResponseException]
 * - [processChoice] - Receives UserChoice parameter from blackboard, produces final result
 *
 * The framework's planner examines action signatures and creates a plan:
 * 1. "getChoice produces UserChoice" (from return type)
 * 2. "processChoice needs UserChoice" (from parameter type)
 * 3. "Execute getChoice, then processChoice"
 *
 * When [getChoice] calls [waitFor], the agent pauses. After user responds via HTTP,
 * [ChoiceAwaitable.onResponse] adds UserChoice to blackboard, allowing [processChoice]
 * to receive it as a parameter when execution resumes.
 */
@Agent(description = "Adventure agent requiring user choices")
class AdventureAgent {

    private val logger = LoggerFactory.getLogger(AdventureAgent::class.java)

    @Action
    fun getChoice(input: UserInput, context: ActionContext): UserChoice {
        logger.info("=== getChoice action starting for player: ${input.content} ===")
        // This throws AwaitableResponseException, never actually returns
        // Return type is UserChoice (awaitable's payload type)
        return waitFor(
            ChoiceAwaitable(
                ChoiceRequest(
                    prompt = "Where do you want to go?",
                    options = listOf("Castle", "Forest", "Cave")
                )
            )
        )
    }

    @Action
    @AchievesGoal(description = "Complete the adventure")
    fun processChoice(choice: UserChoice, context: ActionContext): AdventureResult {
        logger.info("=== processChoice action starting with choice: ${choice.value} ===")
        // This action receives UserChoice from getChoice (via blackboard)
        val result = AdventureResult("You chose: ${choice.value}")
        logger.info("=== processChoice action completed with result: ${result.outcome} ===")
        return result
    }
}

data class StartAdventureRequest(val playerName: String)

data class AwaitableResponseDto(
    val processId: String,
    val awaitableId: String,
    val prompt: String,
    val options: List<String>
)

data class ContinueAdventureRequest(
    val awaitableId: String,
    val choice: String
)

data class AdventureResultDto(
    val outcome: String
)

/**
 * REST controller managing the lifecycle of waiting agent processes.
 *
 * ## POST /adventure/start
 * - Creates new agent process with user input
 * - Runs agent until WAITING or COMPLETED state
 * - Saves process to repository for later resumption
 * - Returns awaitable (question/choices) if WAITING, or final result if COMPLETED
 *
 * ## POST /adventure/{processId}/continue
 * - Loads waiting process from repository
 * - Calls [Awaitable.onResponse] to inject user's response into blackboard
 * - Resumes agent execution by calling [AgentProcess.run]
 * - Saves updated process
 * - Returns next awaitable if still WAITING, or final result if COMPLETED
 *
 * ## Flexible Return Types
 * Both endpoints return [ResponseEntity]<[Any]> because they can return:
 * - [AwaitableResponseDto] when agent is waiting for user input
 * - [AdventureResultDto] when agent has completed
 *
 * This flexibility enables multi-step workflows where the number of user interactions
 * is not known in advance (e.g., username → password → 2FA code).
 */
@ConditionalOnProperty(name = ["waitfor.mvc.test.enabled"], havingValue = "true")
@RestController
@RequestMapping("/adventure")
class AdventureController(
    private val processRepository: InMemoryAgentProcessRepository
) {

    private val logger = LoggerFactory.getLogger(AdventureController::class.java)

    @PostMapping("/start")
    fun start(@RequestBody request: StartAdventureRequest): ResponseEntity<Any> {
        logger.info("=== POST /adventure/start - player: ${request.playerName} ===")
        // Create agent
        val blackboard = InMemoryBlackboard()
        blackboard.addObject(UserInput(request.playerName))

        val agent = AgentMetadataReader()
            .createAgentMetadata(AdventureAgent()) as com.embabel.agent.core.Agent

        val agentProcess = SimpleAgentProcess(
            id = UUID.randomUUID().toString(),
            parentId = null,
            agent = agent,
            processOptions = ProcessOptions.Companion.DEFAULT,
            blackboard = blackboard,
            platformServices = IntegrationTestUtils.dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
            timestamp = Instant.now()
        )

        // Run until WAITING or COMPLETED
        val result = agentProcess.run()
        logger.info("=== Agent process status after run: ${result.status} ===")

        // Save process
        processRepository.save(result)

        // Check status and return appropriate response
        return if (result.status == AgentProcessStatusCode.WAITING) {
            // Get awaitable from blackboard
            val awaitable = result.blackboard.last(ChoiceAwaitable::class.java)
                ?: throw IllegalStateException("Expected ChoiceAwaitable in blackboard")

            ResponseEntity.ok(
                AwaitableResponseDto(
                    processId = result.id,
                    awaitableId = awaitable.id,
                    prompt = awaitable.choiceRequest.prompt,
                    options = awaitable.choiceRequest.options
                )
            )
        } else if (result.status == AgentProcessStatusCode.COMPLETED) {
            // Process completed without waiting
            val finalResult = result.blackboard.last(AdventureResult::class.java)
                ?: throw IllegalStateException("No result found")

            ResponseEntity.ok(
                AdventureResultDto(outcome = finalResult.outcome)
            )
        } else {
            throw IllegalStateException("Unexpected process status: ${result.status}")
        }
    }

    @PostMapping("/{processId}/continue")
    fun continueProcess(
        @PathVariable processId: String,
        @RequestBody request: ContinueAdventureRequest
    ): ResponseEntity<Any> {
        logger.info("=== POST /adventure/$processId/continue - choice: ${request.choice} ===")
        // Load process from repository
        val agentProcess = processRepository.findById(processId)
        if (agentProcess == null) {
            logger.warn("=== Process not found: $processId - returning 404 ===")
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "Process not found: $processId")
        }

        require(agentProcess.status == AgentProcessStatusCode.WAITING) {
            "Process is not waiting: ${agentProcess.status}"
        }

        // Get awaitable from blackboard
        val awaitable = agentProcess.blackboard.last(ChoiceAwaitable::class.java)
            ?: throw IllegalStateException("Expected ChoiceAwaitable in blackboard")

        require(awaitable.id == request.awaitableId) {
            "Awaitable ID mismatch: expected ${awaitable.id}, got ${request.awaitableId}"
        }

        // Create response and call onResponse to inject user's choice
        val userResponse = UserChoiceResponse(
            awaitableId = request.awaitableId,
            choice = request.choice
        )

        awaitable.onResponse(userResponse, agentProcess)
        logger.info("=== onResponse called, resuming agent process ===")

        // Resume execution
        val resumedProcess = agentProcess.run()
        logger.info("=== Agent process status after resume: ${resumedProcess.status} ===")

        // Save updated process
        processRepository.save(resumedProcess)

        // Check status and return appropriate response
        return if (resumedProcess.status == AgentProcessStatusCode.WAITING) {
            // Hit another waitFor - return next awaitable
            val nextAwaitable = resumedProcess.blackboard.last(ChoiceAwaitable::class.java)
                ?: throw IllegalStateException("Expected ChoiceAwaitable in blackboard")

            ResponseEntity.ok(
                AwaitableResponseDto(
                    processId = resumedProcess.id,
                    awaitableId = nextAwaitable.id,
                    prompt = nextAwaitable.choiceRequest.prompt,
                    options = nextAwaitable.choiceRequest.options
                )
            )
        } else if (resumedProcess.status == AgentProcessStatusCode.COMPLETED) {
            // Process completed
            val finalResult = resumedProcess.blackboard.last(AdventureResult::class.java)
                ?: throw IllegalStateException("No result found")

            ResponseEntity.ok(
                AdventureResultDto(outcome = finalResult.outcome)
            )
        } else {
            throw IllegalStateException("Unexpected process status: ${resumedProcess.status}")
        }
    }
}

/**
 * Minimal Spring configuration for WaitFor pattern testing.
 *
 * Uses @Configuration instead of @SpringBootApplication to avoid conflicts
 * with other test application contexts in the module.
 *
 * Scans only the test package to avoid loading unnecessary components
 * that would require additional dependencies (e.g., model providers, external services).
 *
 * This approach ensures fast test startup and minimal mocking requirements.
 */
@Configuration
@ComponentScan(basePackages = ["com.embabel.agent.api.hitl"])
@EnableAutoConfiguration
class WaitForTestApplication

/**
 * Integration test demonstrating the WaitFor pattern with Spring MVC for Human-in-the-Loop (HITL) agent workflows.
 *
 * ## Pattern Overview
 *
 * This test validates how Embabel agents can pause execution using [waitFor], enter a WAITING state,
 * and resume after receiving user input via REST API endpoints. The pattern enables:
 *
 * - **Interactive workflows** - agents that need human decisions mid-execution
 * - **Multi-step forms** - collecting user input across multiple HTTP requests
 * - **Approval workflows** - pausing for human review and approval
 * - **Conversational interfaces** - back-and-forth dialogue with users
 *
 * ## Execution Flow
 *
 * 1. **Start Request** - POST /adventure/start
 *    - Creates agent process with initial input
 *    - Runs agent until it calls [waitFor]
 *    - Agent enters WAITING state
 *    - Returns awaitable (question/choices) to user
 *    - Process saved to repository
 *
 * 2. **Continue Request** - POST /adventure/{processId}/continue
 *    - Loads waiting process from repository
 *    - Calls [Awaitable.onResponse] to inject user's choice into blackboard
 *    - Resumes agent execution from where it paused
 *    - Returns final result or next awaitable if multiple waitFor calls
 *    - Saves updated process
 *
 * ## Key Components
 *
 * - **AdventureAgent** - Two-action agent demonstrating action chaining pattern
 *   - `getChoice()` - calls [waitFor], declares return type matching next action's input
 *   - `processChoice()` - receives UserChoice from blackboard, produces final result
 *
 * - **ChoiceAwaitable** - Custom awaitable implementing [onResponse] transformation
 *   - Payload type is UserChoice (what next action expects)
 *   - Transforms HTTP response → domain object → blackboard
 *
 * - **AdventureController** - REST endpoints managing agent lifecycle
 *   - POST /start - initiates agent, handles WAITING/COMPLETED states
 *   - POST /continue - resumes agent with user input
 *
 * - **InMemoryAgentProcessRepository** - Persists process state between HTTP requests
 *
 * ## Action Chaining
 *
 * The pattern follows "Action A output → Action B input parameter":
 * - `getChoice()` return type: UserChoice
 * - `processChoice()` parameter type: UserChoice
 * - Framework injects UserChoice from blackboard after [onResponse] adds it
 *
 * ## Test Coverage
 *
 * 1. Complete flow - start → WAITING → continue → COMPLETED
 * 2. Different user choices - validates non-hardcoded behavior
 * 3. Error handling - 404 for invalid process IDs
 *
 * @see waitFor
 * @see Awaitable
 * @see AgentProcess
 * @see InMemoryAgentProcessRepository
 */
@SpringBootTest(classes = [WaitForTestApplication::class])
@ActiveProfiles("waitfor")
@TestPropertySource(properties = ["waitfor.mvc.test.enabled=true"])
@AutoConfigureMockMvc
class WaitForMvcIntegrationTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Autowired
    private lateinit var processRepository: InMemoryAgentProcessRepository

    @BeforeEach
    fun setUp() {
        processRepository.clear()
    }

    @Test
    fun `complete adventure flow - start with WAITING and continue to completion`() {

        // ========== OPERATION 1: Start Adventure (MockMVC) ==========
        val startRequest = StartAdventureRequest(playerName = "Player1")

        val startResponse = mockMvc.perform(
            MockMvcRequestBuilders.post("/adventure/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(startRequest))
        )
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.processId").exists())
            .andExpect(MockMvcResultMatchers.jsonPath("$.awaitableId").exists())
            .andExpect(MockMvcResultMatchers.jsonPath("$.prompt").value("Where do you want to go?"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.options").isArray)
            .andExpect(MockMvcResultMatchers.jsonPath("$.options[0]").value("Castle"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.options[1]").value("Forest"))
            .andExpect(MockMvcResultMatchers.jsonPath("$.options[2]").value("Cave"))
            .andReturn()

        val awaitableResponse = objectMapper.readValue(
            startResponse.response.contentAsString,
            AwaitableResponseDto::class.java
        )

        // Verify process is saved in WAITING state
        val waitingProcess = processRepository.findById(awaitableResponse.processId)
        Assertions.assertThat(waitingProcess).isNotNull
        Assertions.assertThat(waitingProcess?.status).isEqualTo(AgentProcessStatusCode.WAITING)

        // Verify awaitable is in blackboard
        val awaitable = waitingProcess?.blackboard?.last(ChoiceAwaitable::class.java)
        Assertions.assertThat(awaitable).isNotNull

        // ========== OPERATION 2: Continue Adventure (MockMVC) ==========
        val continueRequest = ContinueAdventureRequest(
            awaitableId = awaitableResponse.awaitableId,
            choice = "Castle"
        )

        val continueResponse = mockMvc.perform(
            MockMvcRequestBuilders.post("/adventure/${awaitableResponse.processId}/continue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(continueRequest))
        )
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.outcome").value("You chose: Castle"))
            .andReturn()

        val result = objectMapper.readValue(
            continueResponse.response.contentAsString,
            AdventureResultDto::class.java
        )

        Assertions.assertThat(result.outcome).isEqualTo("You chose: Castle")

        // Verify process completed
        val completedProcess = processRepository.findById(awaitableResponse.processId)
        Assertions.assertThat(completedProcess?.status).isEqualTo(AgentProcessStatusCode.COMPLETED)

        // Verify final result in blackboard
        val finalResult = completedProcess?.blackboard?.last(AdventureResult::class.java)
        Assertions.assertThat(finalResult?.outcome).isEqualTo("You chose: Castle")
    }

    @Test
    fun `test different user choice - Forest`() {
        // Start
        val startRequest = StartAdventureRequest(playerName = "Player2")
        val startResponse = mockMvc.perform(
            MockMvcRequestBuilders.post("/adventure/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(startRequest))
        ).andReturn()

        val awaitableResponse = objectMapper.readValue(
            startResponse.response.contentAsString,
            AwaitableResponseDto::class.java
        )

        // Continue with different choice
        val continueRequest = ContinueAdventureRequest(
            awaitableId = awaitableResponse.awaitableId,
            choice = "Forest"
        )

        mockMvc.perform(
            MockMvcRequestBuilders.post("/adventure/${awaitableResponse.processId}/continue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(continueRequest))
        )
            .andExpect(MockMvcResultMatchers.status().isOk)
            .andExpect(MockMvcResultMatchers.jsonPath("$.outcome").value("You chose: Forest"))

        // Verify completion
        val completed = processRepository.findById(awaitableResponse.processId)
        Assertions.assertThat(completed?.status).isEqualTo(AgentProcessStatusCode.COMPLETED)
    }

    @Test
    fun `test process not found returns 404`() {
        val continueRequest = ContinueAdventureRequest(
            awaitableId = "invalid",
            choice = "Castle"
        )

        mockMvc.perform(
            MockMvcRequestBuilders.post("/adventure/non-existent-process-id/continue")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(continueRequest))
        )
            .andExpect(MockMvcResultMatchers.status().isNotFound)
    }

    /**
     * Test configuration providing required beans for WaitFor pattern testing.
     *
     * Nested @TestConfiguration ensures beans are scoped only to this test class,
     * preventing conflicts with other Spring test contexts in the module.
     *
     * Provides [InMemoryAgentProcessRepository] as a Spring bean, even though
     * the class itself is not annotated with @Component. This repository is used
     * by [AdventureController] to persist and retrieve agent process state
     * between HTTP requests.
     */
    @TestConfiguration
    class WaitForTestConfig {

        @Bean
        fun inMemoryAgentProcessRepository(): InMemoryAgentProcessRepository {
            return InMemoryAgentProcessRepository()
        }
    }
}
