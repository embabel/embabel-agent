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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.loop.ToolLoopFactory
import com.embabel.agent.spi.support.AbstractLlmOperations
import com.embabel.agent.spi.support.DefaultToolDecorator
import com.embabel.agent.spi.support.ToolLoopLlmOperations
import com.embabel.agent.spi.support.springai.ChatClientLlmOperations
import com.embabel.common.ai.converters.SchemaGeneratorCustomizer
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.embabel.common.textio.template.TemplateRenderer
import io.mockk.mockk
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

/**
 * Which [LlmOperations] the platform registers, selected by `embabel.agent.platform.llm-operations.type`.
 */
class LlmOperationsSelectionTest {

    private val asyncer = mockk<Asyncer>()
    private val toolLoopFactory = mockk<ToolLoopFactory>()

    private val contextRunner = ApplicationContextRunner()
        .withBean(ModelProvider::class.java, { mockk<ModelProvider>() })
        .withBean(ToolDecorator::class.java, { DefaultToolDecorator() })
        .withBean(Validator::class.java, { Validation.buildDefaultValidatorFactory().validator })
        .withBean(TemplateRenderer::class.java, { JinjavaTemplateRenderer() })
        .withBean(Asyncer::class.java, { asyncer })
        .withBean(ToolLoopFactory::class.java, { toolLoopFactory })
        .withUserConfiguration(ChatClientLlmOperations::class.java, LlmOperationsConfiguration::class.java)

    @Test
    fun `defaults to the Spring AI operations`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(LlmOperations::class.java)).isInstanceOf(ChatClientLlmOperations::class.java)
        }
    }

    @Test
    fun `spring-ai selects the Spring AI operations`() {
        contextRunner
            .withPropertyValues("embabel.agent.platform.llm-operations.type=spring-ai")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(LlmOperations::class.java)).isInstanceOf(ChatClientLlmOperations::class.java)
            }
    }

    @Test
    fun `tool-loop selects the vendor-neutral operations built from platform beans`() {
        contextRunner
            .withPropertyValues("embabel.agent.platform.llm-operations.type=tool-loop")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(ChatClientLlmOperations::class.java)
                val operations = context.getBean(LlmOperations::class.java)
                assertThat(operations).isExactlyInstanceOf(ToolLoopLlmOperations::class.java)
                assertThat(field(AbstractLlmOperations::class.java, "asyncer", operations)).isSameAs(asyncer)
                assertThat(field(ToolLoopLlmOperations::class.java, "toolLoopFactory", operations))
                    .isSameAs(toolLoopFactory)
            }
    }

    @Test
    fun `tool-loop applies the schema generator customizers, as the Spring AI operations do`() {
        val customizer = SchemaGeneratorCustomizer { }
        contextRunner
            .withPropertyValues("embabel.agent.platform.llm-operations.type=tool-loop")
            .withBean(SchemaGeneratorCustomizer::class.java, { customizer })
            .run { context ->
                assertThat(context).hasNotFailed()
                val operations = context.getBean(ToolLoopLlmOperations::class.java)
                assertThat(operations.schemaGeneratorCustomizers).containsExactly(customizer)
            }
    }

    @Test
    fun `tool-loop matches with relaxed binding, as the enum property does`() {
        listOf("TOOL_LOOP", "tool_loop", "Tool-Loop").forEach { value ->
            contextRunner
                .withPropertyValues("embabel.agent.platform.llm-operations.type=$value")
                .run { context ->
                    assertThat(context).hasNotFailed()
                    assertThat(context.getBean(LlmOperations::class.java))
                        .`as`(value)
                        .isExactlyInstanceOf(ToolLoopLlmOperations::class.java)
                }
        }
    }

    @Test
    fun `an unknown type fails startup naming the property instead of registering no LlmOperations`() {
        contextRunner
            .withPropertyValues("embabel.agent.platform.llm-operations.type=langchain")
            .run { context ->
                assertThat(context).hasFailed()
                val messages = generateSequence(context.startupFailure) { it.cause }.mapNotNull { it.message }.toList()
                assertThat(messages).anyMatch { "embabel.agent.platform.llm-operations.type" in it }
            }
    }

    @Test
    fun `a primary LlmOperations bean still wins`() {
        val userOperations = mockk<LlmOperations>()
        contextRunner
            .withPropertyValues("embabel.agent.platform.llm-operations.type=tool-loop")
            .withBean("userLlmOperations", LlmOperations::class.java, { userOperations }, { it.isPrimary = true })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(LlmOperations::class.java)).isSameAs(userOperations)
            }
    }

    private fun field(owner: Class<*>, name: String, target: Any): Any? =
        owner.getDeclaredField(name).apply { isAccessible = true }.get(target)
}
