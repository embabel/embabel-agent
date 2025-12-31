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
package com.embabel.agent.api.annotation.support

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.core.ActionQos
import com.embabel.agent.core.RetryAction
import com.embabel.agent.spi.config.spring.AgentPlatformProperties
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DefaultMethodActionQosProviderTest {

    @Agent(name = "agent", description = "test agent")
    class TestAgent {
        @Action
        fun doIt() {
        }

        @Action(
            retryAction = [
                RetryAction(
                    maxAttempts = [7],
                    idempotent = [true],
                ),
            ],
        )
        fun doWithRetry() {
        }
    }

    @Test
    fun `action qos ordering uses agent then fallback then ActionQos defaults`() {
        val qosProperties = AgentPlatformProperties.ActionQosProperties().apply {
            default = AgentPlatformProperties.ActionQosProperties.ActionProperties(
                maxAttempts = 9,
                backoffMillis = 9000L,
                idempotent = true,
            )
            agents = mapOf(
                "agent" to mapOf(
                    "doIt" to AgentPlatformProperties.ActionQosProperties.ActionProperties(
                        maxAttempts = 3,
                        backoffMultiplier = 2.0,
                    )
                )
            )
        }

        val provider = DefaultMethodActionQosProvider(qosProperties)
        val method = TestAgent::class.java.getMethod("doIt")
        val actionQos = provider.provideActionQos(method, TestAgent())

        assertEquals(3, actionQos.maxAttempts)
        assertEquals(9000L, actionQos.backoffMillis)
        assertEquals(2.0, actionQos.backoffMultiplier)
        assertEquals(ActionQos().backoffMaxInterval, actionQos.backoffMaxInterval)
        assertEquals(true, actionQos.idempotent)
    }

    @Test
    fun `retry action overrides and falls back per property`() {
        val qosProperties = AgentPlatformProperties.ActionQosProperties().apply {
            default = AgentPlatformProperties.ActionQosProperties.ActionProperties(
                maxAttempts = 9,
                backoffMillis = 9000L,
                backoffMultiplier = 3.0,
            )
            agents = mapOf(
                "agent" to mapOf(
                    "doWithRetry" to AgentPlatformProperties.ActionQosProperties.ActionProperties(
                        backoffMultiplier = 2.0,
                    )
                )
            )
        }

        val provider = DefaultMethodActionQosProvider(qosProperties)
        val method = TestAgent::class.java.getMethod("doWithRetry")
        val actionQos = provider.provideActionQos(method, TestAgent())

        assertEquals(7, actionQos.maxAttempts)
        assertEquals(9000L, actionQos.backoffMillis)
        assertEquals(2.0, actionQos.backoffMultiplier)
        assertEquals(ActionQos().backoffMaxInterval, actionQos.backoffMaxInterval)
        assertEquals(true, actionQos.idempotent)
    }
}
