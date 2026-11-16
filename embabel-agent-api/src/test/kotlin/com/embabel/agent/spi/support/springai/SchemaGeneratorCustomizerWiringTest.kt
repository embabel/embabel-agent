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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.api.common.Asyncer
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.support.DefaultToolDecorator
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.common.ai.converters.SchemaGeneratorCustomizer
import com.embabel.common.ai.model.ModelProvider
import com.embabel.common.textio.template.JinjavaTemplateRenderer
import com.embabel.common.textio.template.TemplateRenderer
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder
import io.mockk.mockk
import jakarta.validation.Validation
import jakarta.validation.Validator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.annotation.Order
import java.util.concurrent.Executors

class SchemaGeneratorCustomizerWiringTest {

    @Order(1)
    class FirstCustomizer : SchemaGeneratorCustomizer {
        override fun customize(builder: SchemaGeneratorConfigBuilder) = Unit
    }

    @Order(2)
    class SecondCustomizer : SchemaGeneratorCustomizer {
        override fun customize(builder: SchemaGeneratorConfigBuilder) = Unit
    }

    private val contextRunner = ApplicationContextRunner()
        .withBean(ModelProvider::class.java, { mockk<ModelProvider>() })
        .withBean(ToolDecorator::class.java, { DefaultToolDecorator() })
        .withBean(Validator::class.java, { Validation.buildDefaultValidatorFactory().validator })
        .withBean(TemplateRenderer::class.java, { JinjavaTemplateRenderer() })
        .withBean(Asyncer::class.java, { ExecutorAsyncer(Executors.newCachedThreadPool()) })
        .withBean(ChatClientLlmOperations::class.java)

    @Test
    fun `schema generator customizer beans are applied in bean order`() {
        contextRunner
            .withBean(SecondCustomizer::class.java)
            .withBean(FirstCustomizer::class.java)
            .run { context ->
                assertThat(context).hasNotFailed()
                val customizers = context.getBean(ChatClientLlmOperations::class.java).schemaGeneratorCustomizers
                assertThat(customizers).containsExactly(
                    context.getBean(FirstCustomizer::class.java),
                    context.getBean(SecondCustomizer::class.java),
                )
            }
    }

    @Test
    fun `no schema generator customizer beans are needed`() {
        contextRunner.run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(ChatClientLlmOperations::class.java).schemaGeneratorCustomizers).isEmpty()
        }
    }
}
