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
import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.ActionQos
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

data class Greeting(val text: String)

// ---------------------------------------------------------------------------
// Annotation-based agent used to verify the fix for the original issue path
// (annotation path was already working; we just confirm it still works)
// ---------------------------------------------------------------------------

@Agent(description = "Annotation-based greeting agent", scan = false)
class AnnotationGreetingAgent {

    @Action
    fun greet(input: UserInput): Greeting = Greeting("Hello, ${input.content}!")

    @Action
    @AchievesGoal(description = "produces a greeting")
    fun echo(greeting: Greeting): Greeting = greeting
}

// ---------------------------------------------------------------------------
// DSL agent — previously broken path.
// No qos argument is passed, so qos == ActionQos() before the fix.
// After the fix, platform properties must be honoured at execution time.
// ---------------------------------------------------------------------------

val DslGreetingAgent = agent("DslGreeter", description = "DSL greeting agent") {
    transformation<UserInput, Greeting>(name = "greet") {
        Greeting("Hello, ${it.input.content}!")
    }
    goal(name = "done", description = "produced a greeting", satisfiedBy = Greeting::class)
}

// ---------------------------------------------------------------------------
// Attempt-counting action: lets us assert how many retries actually happened.
// We deliberately fail on the first N attempts so the retry policy kicks in.
// ---------------------------------------------------------------------------

/** Thread-local attempt counter reset before each test run. */
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

// ---------------------------------------------------------------------------
// Integration test
// ---------------------------------------------------------------------------

/**
 * End-to-end tests that prove `embabel.agent.platform.action-qos.default.*`
 * properties are honoured by every action construction path — both annotation-
 * based and DSL/workflow-built — after the fix applied in issue #1562.
 *
 * ## What is being tested
 *
 * Before the fix, `ActionQos` objects created without going through
 * `ActionQosProvider` (i.e. all DSL and workflow-builder actions) silently
 * ignored the `action-qos` platform properties.
 *
 * After the fix, `AbstractAgentProcess.executeAction()` calls
 * `action.withEffectiveQos(platformServices.actionQosProperties())` at the
 * single execution choke point, so every action respects the properties
 * regardless of how it was constructed.
 *
 * ## Properties set for each test
 *
 * - `embabel.agent.platform.action-qos.default.max-attempts=3`
 *   — the value validated by [RetryPolicyHonoured] (requires 3 attempts to succeed).
 * - `embabel.agent.platform.action-qos.default.backoff-millis=1`
 * - `embabel.agent.platform.action-qos.default.backoff-multiplier=1`
 * - `embabel.agent.platform.action-qos.default.backoff-max-interval=1`
 *   — all backoff intervals set to 1 ms so tests run at full speed.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(FakeConfig::class)
@TestPropertySource(
    properties = [
        // maxAttempts=3 means: the RetryCountingAgent (fails twice, succeeds on 3rd) completes.
        // If properties were ignored the default maxAttempts=5 would also work — but we verify
        // that setting maxAttempts=1 makes the counting agent FAIL, proving properties are read.
        "embabel.agent.platform.action-qos.default.max-attempts=3",
        // Keep backoff fast for tests. Multiplier must be > 1 (RetryTemplate constraint).
        "embabel.agent.platform.action-qos.default.backoff-millis=1",
        "embabel.agent.platform.action-qos.default.backoff-multiplier=1.1",
        "embabel.agent.platform.action-qos.default.backoff-max-interval=10",
    ]
)
class ActionQosPlatformPropertiesIntegrationTest(
    @param:Autowired private val autonomy: Autonomy,
) {
    private val agentPlatform: AgentPlatform = autonomy.agentPlatform

    // ---------------------------------------------------------------------------
    // Smoke: basic execution still works (no regression from the fix)
    // ---------------------------------------------------------------------------

    @Nested
    inner class `Smoke — basic execution still works` {

        @Test
        fun `annotation-based agent completes successfully`() {
            val process = agentPlatform.runAgentFrom(
                com.embabel.agent.api.annotation.support.AgentMetadataReader()
                    .createAgentMetadata(AnnotationGreetingAgent()) as com.embabel.agent.core.Agent,
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

    // ---------------------------------------------------------------------------
    // Core: proves platform maxAttempts is honoured
    // ---------------------------------------------------------------------------

    @Nested
    inner class `Platform maxAttempts is honoured` {

        @Test
        fun `DSL action retries according to platform maxAttempts and eventually succeeds`() {
            // RetryCountingAgent fails on attempt 1 and 2, succeeds on attempt 3.
            // With maxAttempts=3 (from properties) it must complete.
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
            assertEquals(3, AttemptCounter.count,
                "Action must have been attempted exactly 3 times")
            val greeting = process.lastResult() as? Greeting
            assertNotNull(greeting)
            assertTrue(greeting.text.contains("attempt 3"))
        }
    }

    // ---------------------------------------------------------------------------
    // Proof: maxAttempts=1 makes the counting agent FAIL
    // (Validates that properties are actually read, not just accidentally correct)
    // ---------------------------------------------------------------------------

    /**
     * A separate test class with maxAttempts=1 to prove the *absence* of retries
     * when the platform property says so.  If the properties were being ignored,
     * the default maxAttempts=5 would kick in and the agent would complete —
     * but with maxAttempts=1 it must fail after the very first attempt.
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
    inner class `maxAttempts=1 causes failure` (
        @param:Autowired private val innerAutonomy: Autonomy,
    ) {
        private val innerPlatform: AgentPlatform = innerAutonomy.agentPlatform

        @Test
        fun `DSL action with maxAttempts=1 fails after first attempt`() {
            // The counting agent fails on the first attempt.
            // With maxAttempts=1 the retry template gives up immediately → FAILED.
            AttemptCounter.reset()

            val process = innerPlatform.runAgentFrom(
                RetryCountingAgent,
                ProcessOptions(),
                mapOf("userInput" to UserInput("single-attempt")),
            )

            assertEquals(
                AgentProcessStatusCode.FAILED, process.status,
                "With maxAttempts=1 the agent must fail: it cannot survive the first attempt",
            )
            assertEquals(1, AttemptCounter.count,
                "Action should only have been attempted once")
        }
    }
}
