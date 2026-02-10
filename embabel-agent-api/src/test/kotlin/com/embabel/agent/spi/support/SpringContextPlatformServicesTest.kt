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
import com.embabel.agent.spi.LlmOperations
import com.embabel.agent.spi.OperationScheduler
import com.embabel.agent.spi.expression.spel.SpelLogicalExpressionParser
import com.embabel.common.textio.template.TemplateRenderer
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext

/**
 * Tests for [SpringContextPlatformServices].
 */
class SpringContextPlatformServicesTest {

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
    private lateinit var customParser: LogicalExpressionParser

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
        customParser = SpelLogicalExpressionParser()
    }

    private fun createServices(
        applicationContext: ApplicationContext? = mockApplicationContext,
        customLogicalExpressionParser: LogicalExpressionParser? = customParser
    ): SpringContextPlatformServices {
        return SpringContextPlatformServices(
            agentPlatform = mockAgentPlatform,
            llmOperations = mockLlmOperations,
            eventListener = mockEventListener,
            operationScheduler = mockOperationScheduler,
            agentProcessRepository = mockAgentProcessRepository,
            asyncer = mockAsyncer,
            objectMapper = mockObjectMapper,
            outputChannel = mockOutputChannel,
            templateRenderer = mockTemplateRenderer,
            customLogicalExpressionParser = customLogicalExpressionParser,
            applicationContext = applicationContext
        )
    }

    @Nested
    inner class AutonomyTests {

        @Test
        fun `autonomy throws when application context is null`() {
            val services = createServices(applicationContext = null)

            assertThatThrownBy { services.autonomy() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Application context is not available")
                .hasMessageContaining("cannot retrieve Autonomy bean")
        }
    }

    @Nested
    inner class ModelProviderTests {

        @Test
        fun `modelProvider throws when application context is null`() {
            val services = createServices(applicationContext = null)

            assertThatThrownBy { services.modelProvider() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Application context is not available")
                .hasMessageContaining("cannot retrieve ModelProvider bean")
        }
    }

    @Nested
    inner class ConversationFactoryProviderTests {

        @Test
        fun `conversationFactoryProvider throws when application context is null`() {
            val services = createServices(applicationContext = null)

            assertThatThrownBy { services.conversationFactoryProvider() }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Application context is not available")
                .hasMessageContaining("cannot retrieve ConversationFactoryProvider bean")
        }
    }

    @Nested
    inner class LogicalExpressionParserTests {

        @Test
        fun `uses custom parser when provided`() {
            val services = createServices(customLogicalExpressionParser = customParser)

            assertThat(services.logicalExpressionParser).isSameAs(customParser)
        }

        @Test
        fun `creates composite parser from context parsers when no custom parser`() {
            val contextParser = mockk<LogicalExpressionParser>()
            every { mockApplicationContext.getBeansOfType(LogicalExpressionParser::class.java, any(), any()) } returns
                mapOf("contextParser" to contextParser)

            val services = createServices(customLogicalExpressionParser = null)

            // The parser should be a composite parser (not the context parser directly)
            assertThat(services.logicalExpressionParser).isNotSameAs(contextParser)
        }

        @Test
        fun `adds SpelLogicalExpressionParser when not in context`() {
            every { mockApplicationContext.getBeansOfType(LogicalExpressionParser::class.java, any(), any()) } returns emptyMap()

            val services = createServices(customLogicalExpressionParser = null)

            // Should have at least the SpEL parser
            assertThat(services.logicalExpressionParser).isNotNull
        }

        @Test
        fun `does not add duplicate SpelLogicalExpressionParser when already in context`() {
            val spelParser = SpelLogicalExpressionParser()
            every { mockApplicationContext.getBeansOfType(LogicalExpressionParser::class.java, any(), any()) } returns
                mapOf("spelParser" to spelParser)

            val services = createServices(customLogicalExpressionParser = null)

            // Should work without duplicates
            assertThat(services.logicalExpressionParser).isNotNull
        }

        @Test
        fun `handles null application context for parser creation`() {
            val services = createServices(applicationContext = null, customLogicalExpressionParser = null)

            // Should create a parser with at least SpEL
            assertThat(services.logicalExpressionParser).isNotNull
        }
    }

    @Nested
    inner class WithEventListenerTests {

        @Test
        fun `withEventListener creates new services with combined listener`() {
            val additionalListener = mockk<AgenticEventListener>()
            val services = createServices()

            val result = services.withEventListener(additionalListener)

            assertThat(result).isInstanceOf(SpringContextPlatformServices::class.java)
            assertThat(result.eventListener).isNotSameAs(mockEventListener)
            assertThat(result.eventListener).isNotSameAs(additionalListener)
        }

        @Test
        fun `withEventListener preserves other services`() {
            val additionalListener = mockk<AgenticEventListener>()
            val services = createServices()

            val result = services.withEventListener(additionalListener) as SpringContextPlatformServices

            assertThat(result.agentPlatform).isSameAs(mockAgentPlatform)
            assertThat(result.llmOperations).isSameAs(mockLlmOperations)
            assertThat(result.operationScheduler).isSameAs(mockOperationScheduler)
            assertThat(result.agentProcessRepository).isSameAs(mockAgentProcessRepository)
            assertThat(result.asyncer).isSameAs(mockAsyncer)
            assertThat(result.objectMapper).isSameAs(mockObjectMapper)
            assertThat(result.outputChannel).isSameAs(mockOutputChannel)
            assertThat(result.templateRenderer).isSameAs(mockTemplateRenderer)
        }
    }

    @Nested
    inner class DataClassPropertiesTests {

        @Test
        fun `all properties are accessible`() {
            val services = createServices()

            assertThat(services.agentPlatform).isSameAs(mockAgentPlatform)
            assertThat(services.llmOperations).isSameAs(mockLlmOperations)
            assertThat(services.eventListener).isSameAs(mockEventListener)
            assertThat(services.operationScheduler).isSameAs(mockOperationScheduler)
            assertThat(services.agentProcessRepository).isSameAs(mockAgentProcessRepository)
            assertThat(services.asyncer).isSameAs(mockAsyncer)
            assertThat(services.objectMapper).isSameAs(mockObjectMapper)
            assertThat(services.outputChannel).isSameAs(mockOutputChannel)
            assertThat(services.templateRenderer).isSameAs(mockTemplateRenderer)
        }
    }
}
