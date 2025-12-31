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
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.core.Agent
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.spi.config.spring.AgentPlatformProperties
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.util.Map
import java.util.concurrent.atomic.AtomicInteger

/**
 *  tests for @Retry annotation.
 */
internal class ActionRetryPolicyAnnotationTest {

    val propertyProvider: ActionQosPropertyProvider = Mockito.mock(ActionQosPropertyProvider::class.java)

    @Test
    fun retryMethodFailsOnlyOnceSucceedsSecond() {
        Mockito.`when`(propertyProvider.getBound("\${retry-twice}"))
            .thenReturn(AgentPlatformProperties.ActionQosProperties.ActionProperties(maxAttempts = 2, backoffMillis = 1))
        val reader = AgentMetadataReader(actionMethodManager = DefaultActionMethodManager(actionQosProvider = DefaultActionQosProvider(propertyProvider = propertyProvider)))
        val instance = AgentWithTwoRetryActions()
        val metadata = reader.createAgentMetadata(instance)

        Assertions.assertNotNull(metadata)
        val agent = metadata as Agent

        val ap = dummyAgentPlatform()
        val agentProcess = ap.createAgentProcess(
            agent,
            ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),
            Map.of<String, RetryTestInput>(
                "input", RetryTestInput("test")
            )
        )


        Assertions.assertDoesNotThrow<RetryTestOutput> {
            agentProcess.run().resultOfType(RetryTestOutput::class.java)
        }

        Assertions.assertEquals(2, instance.retryInvocations.get(), "Retryable method should have been invoked")
    }

}

/**
 * Simple domain class for testing.
 */
internal data class RetryTestInput(val value: String?)

/**
 * Simple output class for testing.
 */
internal data class RetryTestOutput(val result: String?)

/**
 * Agent with two actions with different dynamic costs.
 */
@com.embabel.agent.api.annotation.Agent(
    description = " agent with two dynamic cost actions",
    planner = PlannerType.GOAP
)
internal class AgentWithTwoRetryActions {
    val retryInvocations: AtomicInteger = AtomicInteger(0)

    @AchievesGoal(description = "Process the input")
    @Action(actionRetryPolicyExpression = "\${retry-twice}")
    fun firstAction(input: RetryTestInput?): RetryTestOutput {
        retryInvocations.incrementAndGet()
        if (retryInvocations.get() == 1) throw RuntimeException("Failed!")

        return RetryTestOutput("Success!")
    }
}
