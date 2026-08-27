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
package com.embabel.common.ai.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration
import java.time.Duration

/**
 * The nested `embabel.models.roles` shape has to survive Spring configuration binding, which is a
 * different path from the Kotlin factories: binding sets the `model` field where
 * [LlmOptions.withModel] sets selection criteria.
 */
class RoleConfigurationBindingTest {

    @Configuration
    @EnableConfigurationProperties(ConfigurableModelProviderProperties::class)
    class TestConfig

    private val runner = ApplicationContextRunner()
        .withUserConfiguration(TestConfig::class.java)
        .withConfiguration(AutoConfigurations.of())

    @Test
    fun `binds role to provider to model`() {
        runner
            .withPropertyValues(
                "embabel.models.roles.cheapest.openai.model=gpt-4.1-nano",
                "embabel.models.roles.cheapest.anthropic.model=claude-haiku-4-5",
            )
            .run { context ->
                val properties = context.getBean(ConfigurableModelProviderProperties::class.java)
                assertEquals(
                    setOf("openai", "anthropic"),
                    properties.roles["cheapest"]?.keys,
                )
                assertEquals("gpt-4.1-nano", properties.roles["cheapest"]?.get("openai")?.modelName)
                assertEquals("claude-haiku-4-5", properties.roles["cheapest"]?.get("anthropic")?.modelName)
            }
    }

    @Test
    fun `binds hyperparameters alongside the model`() {
        runner
            .withPropertyValues(
                "embabel.models.roles.best.anthropic.model=claude-sonnet-4-6",
                "embabel.models.roles.best.anthropic.temperature=0.3",
                "embabel.models.roles.best.anthropic.timeout=90s",
                "embabel.models.roles.best.anthropic.max-tokens=4096",
            )
            .run { context ->
                val options = context.getBean(ConfigurableModelProviderProperties::class.java)
                    .roles["best"]?.get("anthropic")
                assertEquals("claude-sonnet-4-6", options?.modelName)
                assertEquals(0.3, options?.temperature)
                assertEquals(Duration.ofSeconds(90), options?.timeout)
                assertEquals(4096, options?.maxTokens)
            }
    }

    @Test
    fun `the flat llms map still binds, unchanged`() {
        runner
            .withPropertyValues(
                "embabel.models.llms.cheapest=gpt-4.1-nano",
                "embabel.models.default-llm=gpt-4.1-mini",
            )
            .run { context ->
                val properties = context.getBean(ConfigurableModelProviderProperties::class.java)
                assertEquals(mapOf("cheapest" to "gpt-4.1-nano"), properties.llms)
                assertEquals("gpt-4.1-mini", properties.defaultLlm)
                assertEquals(emptyMap<String, Map<String, LlmOptions>>(), properties.roles)
            }
    }

    @Test
    fun `bound options report the model they name`() {
        // Binding sets the model field rather than selection criteria, so modelName has to read both.
        runner
            .withPropertyValues("embabel.models.roles.cheapest.openai.model=gpt-4.1-nano")
            .run { context ->
                val options = context.getBean(ConfigurableModelProviderProperties::class.java)
                    .roles["cheapest"]!!["openai"]!!
                assertEquals("gpt-4.1-nano", options.modelName)
                assertNull(options.modelSelectionCriteria)
            }
    }

    @Test
    fun `models named under roles count as well known`() {
        runner
            .withPropertyValues(
                "embabel.models.roles.cheapest.openai.model=gpt-4.1-nano",
                "embabel.models.llms.best=gpt-4.1",
                "embabel.models.default-llm=gpt-4.1-mini",
            )
            .run { context ->
                assertEquals(
                    setOf("gpt-4.1-nano", "gpt-4.1", "gpt-4.1-mini"),
                    context.getBean(ConfigurableModelProviderProperties::class.java).allWellKnownLlmNames(),
                )
            }
    }
}
