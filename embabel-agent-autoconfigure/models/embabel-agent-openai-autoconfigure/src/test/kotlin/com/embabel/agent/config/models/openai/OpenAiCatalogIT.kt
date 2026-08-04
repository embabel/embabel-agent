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
package com.embabel.agent.config.models.openai

import com.embabel.agent.api.common.Ai
import com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration
import com.embabel.agent.config.models.openai.OpenAiIntegrationSupport.sayReady
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.openai.client.okhttp.OpenAIOkHttpClient
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles

@Profile("openai-catalog-test")
@ConfigurationPropertiesScan(basePackages = ["com.embabel.agent"])
@ComponentScan(basePackages = ["com.embabel.agent"])
class OpenAiCatalogTestConfig

/**
 * Holds the shipped catalog against the real OpenAI API.
 *
 * The unit tests prove the catalog is *read* as written; nothing until here proves it describes
 * models that exist. That gap is how `*-pro` shipped broken — declared, never called, build green.
 * Every entry added since carries the same risk, so the two claims a catalog entry makes are
 * checked against the provider: that the id resolves, and that the declared transport works.
 *
 * @see <a href="https://github.com/embabel/embabel-agent/issues/1758">Issue 1758</a>
 */
@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gpt-4o-mini",
        "embabel.agent.platform.models.openai.max-attempts=1",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("openai-catalog-test")
@Import(OpenAiCatalogTestConfig::class, AgentOpenAiAutoConfiguration::class)
@EnabledIfEnvironmentVariable(
    named = "OPENAI_API_KEY",
    matches = ".+",
    disabledReason = "Integration test requires OPENAI_API_KEY",
)
class OpenAiCatalogIT(
    @param:Autowired private val ai: Ai,
    @param:Autowired private val llms: List<LlmService<*>>,
    @param:Autowired private val applicationContext: ApplicationContext,
) {

    /**
     * One `GET /v1/models` covers the whole catalog, so a typo in any entry is caught without
     * calling that model — the cheapest coverage available for the thing most easily got wrong.
     */
    @Nested
    inner class ModelIdsResolve {

        @Test
        fun `every catalog model id is one OpenAI serves`() {
            val definitions = OpenAiModelLoader().loadAutoConfigMetadata()
            val declared = definitions.effectiveModels().map { it.modelId } +
                definitions.embeddingModels.map { it.modelId }

            val client = OpenAIOkHttpClient.builder()
                .apiKey(System.getenv("OPENAI_API_KEY"))
                .build()
            val served = client.models().list().autoPager().map { it.id() }.toSet()

            val unknown = declared.filterNot { it in served }

            assertTrue(
                unknown.isEmpty(),
                "Catalog ids OpenAI does not serve for this project — a typo, a retired model, " +
                    "or one this project is not entitled to: $unknown",
            )
        }
    }

    /**
     * The families added on 2026-08-04, none of which had ever been called. Parameterized rather
     * than one class per model: they differ only by id, and the pro entry — the one whose
     * transport is unusual — earns nothing from being spelled out separately.
     */
    @Nested
    inner class NewFamiliesAnswer {

        @ParameterizedTest(name = "{0} answers over its declared transport")
        @ValueSource(
            strings = [
                "gpt-5.6-sol",
                "gpt-5.6-terra",
                "gpt-5.6-luna",
                "gpt-5.5",
                // Responses-only: Chat Completions is "Not supported" for this one.
                "gpt-5.5-pro",
            ]
        )
        fun `the model replies over the transport the catalog declares`(model: String) {
            val response = sayReady(ai, model)

            assertTrue(
                response.contains("READY", ignoreCase = true),
                "Expected $model to reply READY, got: $response",
            )
        }

        @ParameterizedTest(name = "{0} is registered")
        @ValueSource(strings = ["gpt56sol", "gpt56terra", "gpt56luna", "gpt55", "gpt55pro"])
        fun `the catalog entry becomes a bean`(beanName: String) {
            assertTrue(
                applicationContext.containsBean(beanName),
                "Expected $beanName to be registered from the catalog",
            )
        }

        /** The pro tier is the only new model whose transport differs; pin what it was wired onto. */
        @Test
        fun `the pro tier is wired onto the Responses adapter`() {
            val llm = llms.find { it.name == "gpt-5.5-pro" } as? SpringAiLlmService

            assertInstanceOf(
                OpenAiResponsesChatModel::class.java,
                llm?.chatModel,
                "gpt-5.5-pro on the Chat Completions client fails on every call",
            )
        }
    }
}
