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
package com.embabel.agent.api.common

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.dsl.agent
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.AgentProcess.Companion.withCurrent
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.InjectedType
import com.embabel.agent.core.LlmInvocation
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.core.Usage
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.core.support.SimpleAgentProcess
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.spi.support.DefaultPlannerFactory
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices
import com.embabel.common.ai.model.LlmMetadata
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Duration
import java.time.Instant


// ILLEGAL: constructor-injects ExecutingOperationContext
@Agent(description = "Illegally injects ExecutingOperationContext via constructor", scan = false)
class AgentWithExecutingOperationContextCtor(
    @Suppress("UNUSED_PARAMETER") context: ExecutingOperationContext,
) {
    @AchievesGoal(description = "goal")
    @Action
    fun act(input: UserInput): String = "done"
}

// ILLEGAL: constructor-injects base OperationContext
@Agent(description = "Illegally injects OperationContext via constructor", scan = false)
class AgentWithOperationContextCtor(
    @Suppress("UNUSED_PARAMETER") context: OperationContext,
) {
    @AchievesGoal(description = "goal")
    @Action
    fun act(input: UserInput): String = "done"
}

// LEGAL: injects Ai as an @Action parameter (the correct pattern)
@Agent(description = "Correctly declares Ai as an @Action parameter", scan = false)
class AgentWithAiActionParam {
    @AchievesGoal(description = "goal")
    @Action
    fun act(input: UserInput, ai: Ai): String = "done"
}

// LEGAL: injects nothing (plain action)
@Agent(description = "Plain agent with no special injection", scan = false)
class AgentWithNoInjection {
    @AchievesGoal(description = "goal")
    @Action
    fun act(input: UserInput): String = "done"
}

// Used by the LLM-attribution integration test
@Agent(description = "Records LLM invocation via @Action OperationContext parameter", scan = false)
class ActionParamRecordingAgent {

    @AchievesGoal(description = "Record an invocation")
    @Action
    fun record(input: UserInput, context: OperationContext): String {
        // Correctly receives OperationContext as an @Action parameter.
        // The framework supplies the correct per-invocation instance here.
        context.processContext.agentProcess.recordLlmInvocation(
            LlmInvocation(
                llmMetadata = LlmMetadata(name = "test-model", provider = "test", pricingModel = null),
                usage = Usage(promptTokens = 10, completionTokens = 5, nativeUsage = null),
                timestamp = Instant.now(),
                runningTime = Duration.ofMillis(50),
            )
        )
        return "recorded for ${input.content}"
    }
}

// ---------------------------------------------------------------------------
// Test suite
// ---------------------------------------------------------------------------

/**
 * Tests for the prohibition of [OperationContext] constructor injection in @Agent beans,
 * and the correct alternative pattern of declaring it as an @Action method parameter.
 *
 * Background — the bug (issue #1538):
 * Injecting OperationContext via a Spring bean constructor permanently binds it to a
 * placeholder process ("vigorous_nobel") created at wiring time. When the framework
 * later executes an action under a real process ("cool_dirac"), LLM invocations are
 * attributed to the wrong process, causing costInfoString() to report 0 tokens.
 *
 * Resolution: [AgentMetadataReader] now throws [IllegalStateException] at agent
 * creation time when it detects OperationContext in a constructor, with a clear
 * message pointing to the @Action parameter pattern.
 */
class OperationContextProcessContextResolutionTest {

    // ------------------------------------------------------------------
    // Prohibition tests — AgentMetadataReader must reject illegal patterns
    // ------------------------------------------------------------------

    @Nested
    inner class ConstructorInjectionProhibition {

        private val reader = AgentMetadataReader()

        @Test
        fun `ExecutingOperationContext constructor injection is rejected at agent creation`() {
            val ex = assertThrows<IllegalStateException> {
                reader.createAgentMetadata(
                    AgentWithExecutingOperationContextCtor(
                        ExecutingOperationContext(
                            name = "test",
                            agentProcess = makePlaceholder(),
                        )
                    )
                )
            }
            assertTrue(
                ex.message!!.contains("AgentWithExecutingOperationContextCtor"),
                "Error message must name the offending class, got: ${ex.message}",
            )
            assertTrue(
                ex.message!!.contains("ExecutingOperationContext"),
                "Error message must name the offending type, got: ${ex.message}",
            )
            assertTrue(
                ex.message!!.contains("@Action"),
                "Error message must point to the @Action parameter pattern, got: ${ex.message}",
            )
        }

        @Test
        fun `base OperationContext constructor injection is rejected at agent creation`() {
            val ex = assertThrows<IllegalStateException> {
                reader.createAgentMetadata(
                    AgentWithOperationContextCtor(
                        OperationContext(
                            processContext = makePlaceholder().processContext,
                            operation = InjectedType.named("test"),
                            toolGroups = emptySet(),
                        )
                    )
                )
            }
            assertTrue(ex.message!!.contains("AgentWithOperationContextCtor"))
            assertTrue(ex.message!!.contains("OperationContext"))
            assertTrue(ex.message!!.contains("@Action"))
        }

        @Test
        fun `agent with Ai as action parameter is accepted`() {
            val result = reader.createAgentMetadata(AgentWithAiActionParam())
            assertTrue(result != null, "Valid agent must be accepted by AgentMetadataReader")
        }

        @Test
        fun `agent with no special injection is accepted`() {
            val result = reader.createAgentMetadata(AgentWithNoInjection())
            assertTrue(result != null, "Valid agent must be accepted by AgentMetadataReader")
        }
    }

    // ------------------------------------------------------------------
    // Unit tests — verify the thread-local behaviour that @Action
    // parameter injection relies on under the hood
    // ------------------------------------------------------------------

    @Nested
    inner class ThreadLocalBehaviour {

        private fun makeProcess(id: String) = SimpleAgentProcess(
            id = id,
            parentId = null,
            agent = agent(name = id, description = "placeholder agent for $id") {},
            blackboard = InMemoryBlackboard(),
            processOptions = ProcessOptions(),
            platformServices = dummyPlatformServices(),
            plannerFactory = DefaultPlannerFactory,
        )

        @Test
        fun `AgentProcess withCurrent sets and clears the thread-local correctly`() {
            val process = makeProcess("test-process")

            assertEquals(null, AgentProcess.get(), "Thread-local should be null before withCurrent")

            process.withCurrent {
                assertEquals("test-process", AgentProcess.get()?.id)
            }

            assertEquals(null, AgentProcess.get(), "Thread-local should be null after withCurrent exits")
        }

        @Test
        fun `withCurrent restores previous thread-local on exit`() {
            val outer = makeProcess("outer")
            val inner = makeProcess("inner")

            outer.withCurrent {
                inner.withCurrent {
                    assertEquals("inner", AgentProcess.get()?.id)
                }
                assertEquals("outer", AgentProcess.get()?.id)
            }
            assertEquals(null, AgentProcess.get())
        }
    }

    // ------------------------------------------------------------------
    // Integration test — @Action parameter injection correctly attributes
    // LLM invocations to the executing process
    // ------------------------------------------------------------------

    @Nested
    inner class ActionParameterInjectionIntegration {

        /**
         * Verifies that when OperationContext is declared as an @Action method parameter
         * (the correct pattern), LLM invocations are attributed to the executing process
         * and costInfoString() reports non-zero.
         *
         * This is the positive counterpart to the prohibition tests: it confirms that
         * the recommended fix actually works.
         */
        @Test
        fun `OperationContext as action parameter correctly attributes LLM invocations to executing process`() {
            val ps = dummyPlatformServices()

            val agentMeta = AgentMetadataReader().createAgentMetadata(ActionParamRecordingAgent())
                as com.embabel.agent.core.Agent

            val executingProcess = SimpleAgentProcess(
                id = "cool_dirac",
                parentId = null,
                agent = agentMeta,
                blackboard = InMemoryBlackboard().apply { this += UserInput("Hello") },
                processOptions = ProcessOptions(),
                platformServices = ps,
                plannerFactory = DefaultPlannerFactory,
            )

            executingProcess.run()

            assertEquals(AgentProcessStatusCode.COMPLETED, executingProcess.status)
            assertEquals(
                1,
                executingProcess.llmInvocations.size,
                "cool_dirac must have 1 LLM invocation when OperationContext is an @Action parameter",
            )
            val costInfo = executingProcess.costInfoString(verbose = true)
            assertTrue(costInfo.contains("1 calls")) {
                "costInfoString must report 1 call, got: $costInfo"
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun makePlaceholder() = SimpleAgentProcess(
    id = "vigorous_nobel",
    parentId = null,
    agent = agent(name = "placeholder", description = "placeholder") {},
    blackboard = InMemoryBlackboard(),
    processOptions = ProcessOptions(),
    platformServices = dummyPlatformServices(),
    plannerFactory = DefaultPlannerFactory,
)
