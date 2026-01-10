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

import com.embabel.agent.api.annotation.Action
import com.embabel.agent.api.annotation.Agent
import com.embabel.agent.core.ActionQos
import com.embabel.agent.core.ActionRetryPolicy
import com.embabel.agent.spi.config.spring.AgentPlatformProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.mock
import org.mockito.junit.jupiter.MockitoExtension
import org.springframework.core.env.Environment
import org.springframework.test.context.junit.jupiter.SpringExtension
import kotlin.test.assertEquals

class DefaultActionQosProviderTest {

    val propertyProvider: ActionQosPropertyProvider = Mockito.mock(ActionQosPropertyProvider::class.java)

    @Agent(name = "agent", description = "test agent", actionRetryPolicyExpression = "\${retry-agent}")
    class TestAgent {
        @Action(actionRetryPolicyExpression = "\${do-it-expr}")
        fun doIt() {
        }

        @Action(
            actionRetryPolicy = ActionRetryPolicy.FIRE_ONCE
        )
        fun doWithRetry() {
        }

        @Action(actionRetryPolicyExpression = "\${action-retry-expression}")
        fun doWithRetryExpr() {
        }

        @Action
        fun doWithAgent() {
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
        }

        Mockito.`when`(propertyProvider.getBound("\${do-it-expr}"))
            .thenReturn(AgentPlatformProperties.ActionQosProperties.ActionProperties(maxAttempts = 3, backoffMultiplier = 2.0))

        val provider = DefaultActionQosProvider(qosProperties, propertyProvider)
        val method = TestAgent::class.java.getMethod("doIt")
        val actionQos = provider.provideActionQos(method, TestAgent())

        assertEquals(3, actionQos.maxAttempts)
        assertEquals(9000L, actionQos.backoffMillis)
        assertEquals(2.0, actionQos.backoffMultiplier)
        assertEquals(ActionQos().backoffMaxInterval, actionQos.backoffMaxInterval)
        assertEquals(true, actionQos.idempotent)
    }

    @Test
    fun `retry action overrides and falls back for agent`() {
        val qosProperties = AgentPlatformProperties.ActionQosProperties()

        Mockito.`when`(propertyProvider.getBound("\${retry-agent}"))
            .thenReturn(AgentPlatformProperties.ActionQosProperties.ActionProperties(backoffMultiplier = 2.0))

        val provider = DefaultActionQosProvider(qosProperties, propertyProvider)
        val method = TestAgent::class.java.getMethod("doWithAgent")
        val actionQos = provider.provideActionQos(method, TestAgent())

        assertEquals(ActionQos().maxAttempts, actionQos.maxAttempts)
        assertEquals(ActionQos().backoffMillis, actionQos.backoffMillis)
        assertEquals(2.0, actionQos.backoffMultiplier)
        assertEquals(ActionQos().backoffMaxInterval, actionQos.backoffMaxInterval)
        assertEquals(ActionQos().idempotent, actionQos.idempotent)
    }

    @Test
    fun `retry action overrides and falls back per property`() {
        val qosProperties = AgentPlatformProperties.ActionQosProperties().apply {
            default = AgentPlatformProperties.ActionQosProperties.ActionProperties(
                maxAttempts = 1,
                backoffMillis = 9000L,
                backoffMultiplier = 3.0,
            )
        }

        Mockito.`when`(propertyProvider.getBound("\${action-retry-expression}"))
            .thenReturn(AgentPlatformProperties.ActionQosProperties.ActionProperties(backoffMultiplier = 2.0))

        val provider = DefaultActionQosProvider(qosProperties, propertyProvider)
        val method = TestAgent::class.java.getMethod("doWithRetryExpr")
        val actionQos = provider.provideActionQos(method, TestAgent())

        assertEquals(1, actionQos.maxAttempts)
        assertEquals(9000L, actionQos.backoffMillis)
        assertEquals(2.0, actionQos.backoffMultiplier)
        assertEquals(ActionQos().backoffMaxInterval, actionQos.backoffMaxInterval)
        assertEquals(ActionQos().idempotent, actionQos.idempotent)
    }

    @Test
    fun `retry action overrides with fire once`() {
        val qosProperties = AgentPlatformProperties.ActionQosProperties()

        val provider = DefaultActionQosProvider(qosProperties, propertyProvider)
        val method = TestAgent::class.java.getMethod("doWithRetry")
        val actionQos = provider.provideActionQos(method, TestAgent())

        assertEquals(1, actionQos.maxAttempts)
        assertEquals(ActionQos().backoffMillis, actionQos.backoffMillis)
        assertEquals(ActionQos().backoffMultiplier, actionQos.backoffMultiplier)
        assertEquals(ActionQos().backoffMaxInterval, actionQos.backoffMaxInterval)
        assertEquals(ActionQos().idempotent, actionQos.idempotent)
    }
}
