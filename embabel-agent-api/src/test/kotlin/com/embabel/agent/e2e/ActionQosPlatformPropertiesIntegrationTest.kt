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
package com.embabel.agent.e2e

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

data class Greeting(val text: String)

@Agent(description = "Annotation-based greeting agent", scan = false)
class AnnotationGreetingAgent {

    @Action
    fun greet(input: UserInput): Greeting = Greeting("Hello, ${input.content}!")

    @Action
    @AchievesGoal(description = "produces a greeting")
    fun echo(greeting: Greeting): Greeting = greeting
}

val DslGreetingAgent = agent("DslGreeter", description = "DSL greeting agent") {
    transformation<UserInput, Greeting>(name = "greet") {
        Greeting("Hello, ${it.input.content}!")
    }
    goal(name = "done", description = "produced a greeting", satisfiedBy = Greeting::class)
}

/** Shared mutable counter — reset before each test. */
object AttemptCounter {
    var count = 0
    fun reset() { count = 0 }
}

val RetryCountingAgent = agent("RetryCounter", description = "Counts retry attempts") {
    // Fails twice then succeeds — requires maxAttempts >= 3 to complete.
    transformation<UserInput, Greeting>(name = "maybe-fail") {
        AttemptCounter.count++
        if (AttemptCounter.count < 3) {
            throw RuntimeException("Simulated failure on attempt ${AttemptCounter.count}")
        }
        Greeting("Succeeded on attempt ${AttemptCounter.count}")
    }
    goal(name = "done", description = "produced a greeting", satisfiedBy = Greeting::class)
}

/**
 * End-to-end tests proving that `embabel.agent.platform.action-qos.default.*`
 * properties are honoured for every action construction path (annotation-based
 * and DSL/workflow-built) after the fix for issue #1562.
 *
 * The falsification test (proving properties are *actively* read, not just
 * accidentally compatible with defaults) lives in the companion top-level class
 * [ActionQosSingleAttemptIntegrationTest], which runs under a separate Spring
 * context with `max-attempts=1`.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeConfig::class)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.action-qos.default.max-attempts=3",
        // Keep backoff fast. Multiplier must be > 1 (RetryTemplate constraint).
        "embabel.agent.platform.action-qos.default.backoff-millis=1",
        "embabel.agent.platform.action-qos.default.backoff-multiplier=1.1",
        "embabel.agent.platform.action-qos.default.backoff-max-interval=10",
    ]
)
class ActionQosPlatformPropertiesIntegrationTest(
    @param:Autowired private val autonomy: Autonomy,
) {
    private val agentPlatform: AgentPlatform = autonomy.agentPlatform

    @Nested
    inner class `Smoke — basic execution still works` {

        @Test
        fun `annotation-based agent completes successfully`() {
            val process = agentPlatform.runAgentFrom(
                AgentMetadataReader().createAgentMetadata(AnnotationGreetingAgent()) as com.embabel.agent.core.Agent,
                ProcessOptions(),
                mapOf("userInput" to UserInput("World")),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
            val greeting = process.lastResult() as? Greeting
            assertNotNull(greeting)
            assertTrue(greeting.text.contains("World"))
        }

        @Test
        fun `DSL agent completes successfully`() {
            val process = agentPlatform.runAgentFrom(
                DslGreetingAgent,
                ProcessOptions(),
                mapOf("userInput" to UserInput("Kotlin")),
            )
            assertEquals(AgentProcessStatusCode.COMPLETED, process.status)
            val greeting = process.lastResult() as? Greeting
            assertNotNull(greeting)
            assertTrue(greeting.text.contains("Kotlin"))
        }
    }

    @Nested
    inner class `Platform maxAttempts is honoured` {

        @Test
        fun `DSL action retries according to platform maxAttempts and eventually succeeds`() {
            // RetryCountingAgent fails on attempts 1 and 2, succeeds on attempt 3.
            // With maxAttempts=3 from properties it must complete.
            AttemptCounter.reset()

            val process = agentPlatform.runAgentFrom(
                RetryCountingAgent,
                ProcessOptions(),
                mapOf("userInput" to UserInput("retry-test")),
            )

            assertEquals(
                AgentProcessStatusCode.COMPLETED, process.status,
                "Agent should complete — maxAttempts=3 from platform properties allows 3 attempts",
            )
            assertEquals(3, AttemptCounter.count, "Action must have been attempted exactly 3 times")
            val greeting = process.lastResult() as? Greeting
            assertNotNull(greeting)
            assertTrue(greeting.text.contains("attempt 3"))
        }
    }
}

/**
 * Companion to [ActionQosPlatformPropertiesIntegrationTest].
 *
 * Runs under a separate Spring context with `max-attempts=1` to prove that
 * platform properties are actively read. A separate top-level class is required
 * because `@SpringBootTest` + `@TestPropertySource` cannot be meaningfully
 * overridden in a `@Nested` inner class — each needs its own application context.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeConfig::class)
@TestPropertySource(
    properties = [
        "embabel.agent.platform.action-qos.default.max-attempts=1",
        "embabel.agent.platform.action-qos.default.backoff-millis=1",
        "embabel.agent.platform.action-qos.default.backoff-multiplier=1.1",
        "embabel.agent.platform.action-qos.default.backoff-max-interval=10",
    ]
)
class ActionQosSingleAttemptIntegrationTest(
    @param:Autowired private val autonomy: Autonomy,
) {
    private val agentPlatform: AgentPlatform = autonomy.agentPlatform

    @Test
    fun `DSL action with maxAttempts=1 throws after single attempt`() {
        AttemptCounter.reset()

        assertThrows<RuntimeException> {
            agentPlatform.runAgentFrom(
                RetryCountingAgent,
                ProcessOptions(),
                mapOf("userInput" to UserInput("single-attempt")),
            )
        }

        assertEquals(1, AttemptCounter.count,
            "Action should only have been attempted once with maxAttempts=1")
    }
}
