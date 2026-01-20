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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.Provided
import com.embabel.agent.api.annotation.State
import com.embabel.agent.core.AgentProcessStatusCode
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import com.embabel.agent.core.Agent as CoreAgent

/**
 * Integration tests for the @Provided annotation.
 *
 * These tests verify that parameters annotated with @Provided are correctly
 * resolved from the ContextProvider rather than the blackboard.
 */
class ProvidedAnnotationIntegrationTest {

    private val reader = AgentMetadataReader(
        actionMethodManager = DefaultActionMethodManager(
            contextProvider = TestContextProvider,
        ),
    )

    @Test
    fun `action with @Provided parameter resolves from context provider`() {
        val agent = reader.createAgentMetadata(AgentWithProvidedService()) as CoreAgent
        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val process = ap.runAgentFrom(
            agent,
            ProcessOptions(),
            mapOf("it" to ProvidedTestInput("hello")),
        )
        assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")
        val output = process.getValue("it", ProvidedTestOutput::class.java.name) as? ProvidedTestOutput
        assertNotNull(output, "Should have output")
        assertEquals("hello-processed-by-TestService", output!!.result)
    }

    @Test
    fun `state action with @Provided parameter resolves from context provider`() {
        val agent = reader.createAgentMetadata(AgentWithProvidedServiceInState()) as CoreAgent
        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val process = ap.runAgentFrom(
            agent,
            ProcessOptions(),
            mapOf("it" to ProvidedTestInput("world")),
        )
        assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")
        val output = process.getValue("it", ProvidedTestOutput::class.java.name) as? ProvidedTestOutput
        assertNotNull(output, "Should have output")
        assertEquals("world-processed-in-state-by-TestService", output!!.result)
    }

    @Test
    fun `@Provided parameter coexists with blackboard parameters`() {
        val agent = reader.createAgentMetadata(AgentWithMixedParameters()) as CoreAgent
        val ap = IntegrationTestUtils.dummyAgentPlatform()
        val process = ap.runAgentFrom(
            agent,
            ProcessOptions(),
            mapOf("it" to ProvidedTestInput("mixed")),
        )
        assertEquals(AgentProcessStatusCode.COMPLETED, process.status, "Agent should complete")
        val output = process.getValue("it", ProvidedTestOutput::class.java.name) as? ProvidedTestOutput
        assertNotNull(output, "Should have output")
        assertEquals("mixed-combined-with-TestService-and-ProvidedExtraData", output!!.result)
    }
}

// Test context provider that provides test services
object TestContextProvider : ContextProvider {
    val testService = TestService()

    override fun <T : Any> getFromContext(type: Class<T>): T? {
        @Suppress("UNCHECKED_CAST")
        return when (type) {
            TestService::class.java -> testService as T
            else -> null
        }
    }

    override fun hasInContext(type: Class<*>): Boolean {
        return type == TestService::class.java
    }
}

// Test service to be injected
class TestService {
    fun process(input: String): String = "$input-processed-by-TestService"
    fun processInState(input: String): String = "$input-processed-in-state-by-TestService"
    fun combine(input: String, extra: String): String = "$input-combined-with-TestService-and-$extra"
}

// Test input/output types
data class ProvidedTestInput(val content: String)
data class ProvidedTestOutput(val result: String)
data class ProvidedExtraData(val value: String)

/**
 * Agent that uses @Provided in a top-level action
 */
@Agent(description = "Agent with provided service")
class AgentWithProvidedService {

    @AchievesGoal(description = "Process input with provided service")
    @Action
    fun processWithService(
        input: ProvidedTestInput,
        @Provided service: TestService,
    ): ProvidedTestOutput {
        return ProvidedTestOutput(service.process(input.content))
    }
}

/**
 * Agent that uses @Provided in a state action
 */
@Agent(description = "Agent with provided service in state")
class AgentWithProvidedServiceInState {

    @Action
    fun start(input: ProvidedTestInput): ProcessingState = ProcessingState(input.content)

    @State
    data class ProcessingState(val data: String) {
        @AchievesGoal(description = "Processed in state with provided service")
        @Action
        fun process(@Provided service: TestService): ProvidedTestOutput {
            return ProvidedTestOutput(service.processInState(data))
        }
    }
}

/**
 * Agent that mixes @Provided with blackboard parameters
 */
@Agent(description = "Agent with mixed parameters")
class AgentWithMixedParameters {

    @Action
    fun start(input: ProvidedTestInput): IntermediateState {
        return IntermediateState(input.content)
    }

    @State
    data class IntermediateState(val data: String) {
        @Action
        fun prepareExtra(): ProvidedExtraData = ProvidedExtraData("ProvidedExtraData")
    }

    @AchievesGoal(description = "Combined result")
    @Action
    fun combine(
        state: IntermediateState,
        extra: ProvidedExtraData,              // From blackboard
        @Provided service: TestService, // From context
    ): ProvidedTestOutput {
        return ProvidedTestOutput(service.combine(state.data, extra.value))
    }
}
