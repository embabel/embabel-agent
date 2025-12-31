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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.api.annotation.AchievesGoal
import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.support.AgentMetadataReader
import com.embabel.agent.api.annotation.support.DefaultActionMethodManager
import com.embabel.agent.api.annotation.support.DefaultMethodActionQosProvider
import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.core.ActionQos
import com.embabel.agent.core.Agent
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.test.integration.IntegrationTestUtils.dummyAgentPlatform
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.ThrowingSupplier
import java.util.Map
import java.util.concurrent.atomic.AtomicInteger


/**
 * Java tests for changing retry for action using properties.
 */
internal class RetryActionPropertiesTest {
    @Test
    fun retryMethodFailsOnlyOnceSucceedsSecond() {
        val methodActionQosProvider = DefaultMethodActionQosProvider()
        methodActionQosProvider.perActionQosProperties.default =
            AgentPlatformProperties.ActionQosProperties.ActionProperties(maxAttempts = 2, backoffMillis = 1L)
        val reader =
            AgentMetadataReader(actionMethodManager = DefaultActionMethodManager(methodActionQosProvider = methodActionQosProvider))
        val instance = JavaAgentWithTwoRetryPropertiesActions()
        val metadata = reader.createAgentMetadata(instance)

        Assertions.assertNotNull(metadata)
        val agent = metadata as Agent

        val ap = dummyAgentPlatform()
        val agentProcess = ap.createAgentProcess(
            agent,
            ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),
            Map.of<String, JavaRetryTestInput>(
                "input", JavaRetryTestInput("test")
            )
        )


        Assertions.assertDoesNotThrow(ThrowingSupplier {
            agentProcess.run().resultOfType(JavaRetryTestOutput::class.java)
        })

        Assertions.assertEquals(2, instance.retryInvocations.get(), "Retryable method should have been invoked")
    }

    @Test
    fun retryMethodFailsOnlyOnceSucceedsSecondAgent() {
        val methodActionQosProvider = DefaultMethodActionQosProvider()
        methodActionQosProvider.perActionQosProperties.agents = mutableMapOf(
            Pair(
                "JavaAgentWithTwoRetryPropertiesActions", mutableMapOf(
                    Pair(
                        "perform",
                        AgentPlatformProperties.ActionQosProperties.ActionProperties(maxAttempts = 2, backoffMillis = 1L)
                    )
                )
            )
        )
        val reader =
            AgentMetadataReader(actionMethodManager = DefaultActionMethodManager(methodActionQosProvider = methodActionQosProvider))
        val instance = JavaAgentWithTwoRetryPropertiesActions()
        val metadata = reader.createAgentMetadata(instance)

        Assertions.assertNotNull(metadata)
        val agent = metadata as Agent

        val ap = dummyAgentPlatform()
        val agentProcess = ap.createAgentProcess(
            agent,
            ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),
            Map.of<String, JavaRetryTestInput>(
                "input", JavaRetryTestInput("test")
            )
        )


        Assertions.assertDoesNotThrow(ThrowingSupplier {
            agentProcess.run().resultOfType(JavaRetryTestOutput::class.java)
        })

        Assertions.assertEquals(2, instance.retryInvocations.get(), "Retryable method should have been invoked")
    }

    @Test
    fun retryMethodFailsOnlyOnce() {
        val methodActionQosProvider = DefaultMethodActionQosProvider()
        methodActionQosProvider.perActionQosProperties.default = AgentPlatformProperties.ActionQosProperties.ActionProperties(maxAttempts = 1, backoffMillis = 1L)
        val reader =
            AgentMetadataReader(actionMethodManager = DefaultActionMethodManager(methodActionQosProvider = methodActionQosProvider))
        val instance = JavaAgentWithPropertiesRetry()
        val metadata = reader.createAgentMetadata(instance)

        Assertions.assertNotNull(metadata)
        val agent = metadata as Agent

        val ap = dummyAgentPlatform()
        val agentProcess = ap.createAgentProcess(
            agent,
            ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),
            Map.of<String, JavaRetryTestInput>(
                "input", JavaRetryTestInput("test")
            )
        )


        Assertions.assertThrows(RuntimeException::class.java) {
            agentProcess.run().resultOfType(JavaRetryTestOutput::class.java)
        }

        Assertions.assertEquals(1, instance.retryInvocationCount.get(), "Retryable method should have been invoked")
    }

    @Test
    fun retryMethodFailsOnlyOnceAgentName() {
        val methodActionQosProvider = DefaultMethodActionQosProvider()
        methodActionQosProvider.perActionQosProperties.agents = mutableMapOf(
            Pair(
                "JavaAgentWithPropertiesRetry",
                mutableMapOf(Pair("perform", AgentPlatformProperties.ActionQosProperties.ActionProperties(maxAttempts = 1, backoffMillis = 1L)))
            )
        )
        val reader =
            AgentMetadataReader(actionMethodManager = DefaultActionMethodManager(methodActionQosProvider = methodActionQosProvider))
        val instance = JavaAgentWithPropertiesRetry()
        val metadata = reader.createAgentMetadata(instance)

        Assertions.assertNotNull(metadata)
        val agent = metadata as Agent

        val ap = dummyAgentPlatform()
        val agentProcess = ap.createAgentProcess(
            agent,
            ProcessOptions.DEFAULT.withPlannerType(PlannerType.UTILITY),
            Map.of<String, JavaRetryTestInput>(
                "input", JavaRetryTestInput("test")
            )
        )


        Assertions.assertThrows(RuntimeException::class.java) {
            agentProcess.run().resultOfType(JavaRetryTestOutput::class.java)
        }

        Assertions.assertEquals(1, instance.retryInvocationCount.get(), "Retryable method should have been invoked")
    }
}

/**
 * Simple domain class for testing.
 */
data class JavaRetryTestInput(val value: String?)

/**
 * Simple output class for testing.
 */
data class JavaRetryTestOutput(val result: String?)

/**
 * Agent with @Cost method that uses nullable domain parameter.
 */
@com.embabel.agent.api.annotation.Agent(
    description = "Java agent with 1 retry",
    planner = PlannerType.UTILITY,
    name = "JavaAgentWithPropertiesRetry"
)
internal class JavaAgentWithPropertiesRetry {
    val retryInvocationCount: AtomicInteger = AtomicInteger(0)

    @Action
    fun perform(input: JavaRetryTestInput?): JavaRetryTestOutput? {
        retryInvocationCount.incrementAndGet()
        throw RuntimeException("Failed on purpose!")
    }
}

/**
 * Agent with two actions with different dynamic costs.
 */
@com.embabel.agent.api.annotation.Agent(
    description = "Java agent with two dynamic cost actions",
    planner = PlannerType.GOAP,
    name = "JavaAgentWithTwoRetryPropertiesActions",
)
internal class JavaAgentWithTwoRetryPropertiesActions {
    val retryInvocations: AtomicInteger = AtomicInteger(0)

    @AchievesGoal(description = "Process the input")
    @Action
    fun perform(input: JavaRetryTestInput?): JavaRetryTestOutput {
        retryInvocations.incrementAndGet()
        if (retryInvocations.get() == 1) throw RuntimeException("Failed!")

        return JavaRetryTestOutput("Success!")
    }
}
