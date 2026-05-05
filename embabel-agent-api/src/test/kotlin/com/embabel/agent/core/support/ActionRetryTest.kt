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
package com.embabel.agent.core.support

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.api.annotation.support.ActionQosPropertyProvider
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.annotation.support.DefaultActionMethodManager
import com.embabel.agent.api.annotation.support.DefaultActionQosProvider
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.core.ActionException
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.config.spring.AgentPlatformProperties
import com.embabel.agent.test.integration.IntegrationTestUtils
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.concurrent.atomic.AtomicInteger
import com.embabel.agent.core.Agent as CoreAgent

/**
 * Integration tests for action retry behavior.
 */
class ActionRetryTest {

    @Nested
    inner class RetryExceptionTests {

        private val propertyProvider: ActionQosPropertyProvider = Mockito.mock(ActionQosPropertyProvider::class.java)
        private val fastReader: AgentMetadataReader

        init {
            Mockito.`when`(propertyProvider.getBound("\${fast-retry}"))
                .thenReturn(AgentPlatformProperties.ActionQosProperties.ActionProperties(
                    maxAttempts = 5,
                    backoffMillis = 20
                ))
            fastReader = AgentMetadataReader(
                actionMethodManager = DefaultActionMethodManager(
                    actionQosProvider = DefaultActionQosProvider(propertyProvider = propertyProvider)
                )
            )
        }

        @Test
        fun `RetryableException enables retry`() {
            val instance = RetryableSuccessAgent()
            val agent = fastReader.createAgentMetadata(instance) as CoreAgent

            val agentProcess = IntegrationTestUtils.dummyAgentPlatform().createAgentProcess(
                agent,
                ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),
                mapOf("input" to TestInput("test"))
            )

            agentProcess.run()

            // Should succeed after 2 attempts
            assertEquals(2, instance.attempts.get())
        }

        @Test
        fun `NonRetryableException stops immediately`() {
            val instance = NonRetryableAgent()
            val agent = fastReader.createAgentMetadata(instance) as CoreAgent

            val agentProcess = IntegrationTestUtils.dummyAgentPlatform().createAgentProcess(
                agent,
                ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),
                mapOf("input" to TestInput("test"))
            )

            // NonRetryableException should be re-thrown without retry
            try {
                agentProcess.run()
            } catch (e: ActionException.NonRetryable) {
                // Expected - exception should propagate after first failure
            }

            // Should only try once (no retry)
            assertEquals(1, instance.attempts.get())
        }
    }

    @Nested
    inner class EffectConditionTests {

        private val propertyProvider: ActionQosPropertyProvider = Mockito.mock(ActionQosPropertyProvider::class.java)
        private val fastReader: AgentMetadataReader

        init {
            Mockito.`when`(propertyProvider.getBound("\${fast-retry}"))
                .thenReturn(AgentPlatformProperties.ActionQosProperties.ActionProperties(
                    maxAttempts = 5,
                    backoffMillis = 20
                ))
            fastReader = AgentMetadataReader(
                actionMethodManager = DefaultActionMethodManager(
                    actionQosProvider = DefaultActionQosProvider(propertyProvider = propertyProvider)
                )
            )
        }

        @Test
        fun `effect conditions cleared on retry`() {
            val instance = EffectConditionAgent()
            val agent = fastReader.createAgentMetadata(instance) as CoreAgent

            val agentProcess = IntegrationTestUtils.dummyAgentPlatform().createAgentProcess(
                agent,
                ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),
                mapOf("input" to TestInput("test"))
            )

            agentProcess.run()

            // Should succeed after 2 attempts
            assertEquals(2, instance.attempts.get())
            // Effect condition (in post) should be cleared on retry
            assertEquals(false, instance.effectConditionState)
            // Other condition (NOT in post) should remain unchanged
            assertEquals(true, instance.otherConditionState)
        }
    }

    // Test domain classes
    data class TestInput(val value: String)
    data class TestOutput(val result: String)

    // Test agent classes

    @Agent(description = "Agent with retryable that succeeds")
    internal class RetryableSuccessAgent {
        val attempts = AtomicInteger(0)

        @Action(
            description = "Fails once then succeeds",
            actionRetryPolicyExpression = "\${fast-retry}"
        )
        @AchievesGoal(description = "Process")
        fun process(input: TestInput): TestOutput {
            val attempt = attempts.incrementAndGet()

            if (attempt == 1) {
                throw ActionException.Retryable("First attempt fails")
            }

            return TestOutput("Success on attempt $attempt")
        }
    }

    @Agent(description = "Agent with non-retryable error")
    internal class NonRetryableAgent {
        val attempts = AtomicInteger(0)

        @Action(
            description = "Non-retryable action",
            actionRetryPolicyExpression = "\${fast-retry}"
        )
        @AchievesGoal(description = "Process")
        fun process(input: TestInput): TestOutput {
            attempts.incrementAndGet()
            throw ActionException.NonRetryable("Validation error")
        }
    }

    @Agent(description = "Agent with effect conditions")
    internal class EffectConditionAgent {
        val attempts = AtomicInteger(0)
        var effectConditionState: Boolean? = null
        var otherConditionState: Boolean? = null

        @Action(
            description = "Action with post-condition",
            post = ["dataProcessed"],
            actionRetryPolicyExpression = "\${fast-retry}"
        )
        @AchievesGoal(description = "Process")
        fun process(input: TestInput, context: OperationContext): TestOutput {
            val attempt = attempts.incrementAndGet()

            if (attempt == 1) {
                // First attempt sets both conditions then fails
                context.setCondition("dataProcessed", true)
                context.setCondition("otherCondition", true)
                throw ActionException.Retryable("First attempt fails")
            }

            // On retry, only effect condition (in post) should be cleared
            effectConditionState = context.getCondition("dataProcessed")
            otherConditionState = context.getCondition("otherCondition")
            return TestOutput("Success on attempt $attempt")
        }
    }
}
