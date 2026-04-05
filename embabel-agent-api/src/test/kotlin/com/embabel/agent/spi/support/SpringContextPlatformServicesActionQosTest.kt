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
package com.embabel.agent.spi.support

import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcessRepository
import com.embabel.agent.core.expression.LogicalExpressionParser
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.spi.OperationScheduler
import com.embabel.agent.spi.config.spring.AgentPlatformProperties
import com.embabel.agent.spi.expression.spel.SpelLogicalExpressionParser
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext

/**
 * Unit tests for [SpringContextPlatformServices.actionQosProperties].
 *
 * Covers:
 * - null application context (test/unit-test fallback — same path as [com.embabel.agent.test.integration.IntegrationTestUtils.dummyPlatformServices])
 * - context present, [com.embabel.agent.spi.config.spring.AgentPlatformProperties] bean found with QoS configured
 * - context present, [com.embabel.agent.spi.config.spring.AgentPlatformProperties] bean found with no QoS override
 * - context present, [com.embabel.agent.spi.config.spring.AgentPlatformProperties] bean not registered (safe fallback via [org.springframework.context.ApplicationContext.getBeansOfType])
 */
class SpringContextPlatformServicesActionQosTest {

    private lateinit var mockAgentPlatform: AgentPlatform
    private lateinit var mockLlmOperations: LlmOperations
    private lateinit var mockEventListener: AgenticEventListener
    private lateinit var mockOperationScheduler: OperationScheduler
    private lateinit var mockAgentProcessRepository: AgentProcessRepository
    private lateinit var mockAsyncer: Asyncer
    private lateinit var mockObjectMapper: ObjectMapper
    private lateinit var mockOutputChannel: OutputChannel
    private lateinit var mockTemplateRenderer: TemplateRenderer
    private lateinit var mockApplicationContext: ApplicationContext

    @BeforeEach
    fun setup() {
        mockAgentPlatform = mockk()
        mockLlmOperations = mockk()
        mockEventListener = mockk()
        mockOperationScheduler = mockk()
        mockAgentProcessRepository = mockk()
        mockAsyncer = mockk()
        mockObjectMapper = mockk()
        mockOutputChannel = mockk()
        mockTemplateRenderer = mockk()
        mockApplicationContext = mockk()
    }

    private fun createServices(applicationContext: ApplicationContext?): SpringContextPlatformServices =
        SpringContextPlatformServices(
            agentPlatform = mockAgentPlatform,
            llmOperations = mockLlmOperations,
            eventListener = mockEventListener,
            operationScheduler = mockOperationScheduler,
            agentProcessRepository = mockAgentProcessRepository,
            asyncer = mockAsyncer,
            objectMapper = mockObjectMapper,
            outputChannel = mockOutputChannel,
            templateRenderer = mockTemplateRenderer,
            customLogicalExpressionParser = SpelLogicalExpressionParser(),
            applicationContext = applicationContext,
        )

    // ---------------------------------------------------------------------------

    @Nested
    inner class `Null application context` {

        @Test
        fun `returns empty ActionQosProperties when context is null`() {
            // This is the path taken by dummyPlatformServices() in unit tests.
            val services = createServices(applicationContext = null)

            val props = services.actionQosProperties()

            // All-null default = no-op — preserves pre-fix behaviour.
            assertThat(props).isNotNull
            assertThat(props.default.maxAttempts).isNull()
            assertThat(props.default.backoffMillis).isNull()
            assertThat(props.default.backoffMultiplier).isNull()
            assertThat(props.default.backoffMaxInterval).isNull()
            assertThat(props.default.idempotent).isNull()
        }
    }

    @Nested
    inner class `Application context present` {

        @Test
        fun `returns configured ActionQosProperties from context`() {
            val platformProperties = AgentPlatformProperties().apply {
                actionQos = AgentPlatformProperties.ActionQosProperties().apply {
                    default = AgentPlatformProperties.ActionQosProperties.ActionProperties(
                        maxAttempts = 2,
                        backoffMillis = 500L,
                        idempotent = true,
                    )
                }
            }
            every {
                mockApplicationContext.getBeansOfType(AgentPlatformProperties::class.java, any(), any())
            } returns mapOf("agentPlatformProperties" to platformProperties)

            val services = createServices(mockApplicationContext)
            val props = services.actionQosProperties()

            assertThat(props.default.maxAttempts).isEqualTo(2)
            assertThat(props.default.backoffMillis).isEqualTo(500L)
            assertThat(props.default.idempotent).isTrue()
        }

        @Test
        fun `returns all-null properties when AgentPlatformProperties has no qos override`() {
            val platformProperties = AgentPlatformProperties()   // actionQos.default has all-null fields
            every {
                mockApplicationContext.getBeansOfType(AgentPlatformProperties::class.java, any(), any())
            } returns mapOf("agentPlatformProperties" to platformProperties)

            val services = createServices(mockApplicationContext)
            val props = services.actionQosProperties()

            // All fields null → withEffectiveQos() will treat it as no-op.
            assertThat(props.default.maxAttempts).isNull()
        }

        @Test
        fun `returns empty fallback when AgentPlatformProperties bean is not registered`() {
            // getBeansOfType returns empty map instead of throwing NoSuchBeanDefinitionException.
            every {
                mockApplicationContext.getBeansOfType(AgentPlatformProperties::class.java, any(), any())
            } returns emptyMap()

            val services = createServices(mockApplicationContext)
            val props = services.actionQosProperties()

            // Safe fallback — same result as null context.
            assertThat(props.default.maxAttempts).isNull()
        }
    }
}
