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

import com.embabel.agent.openai.Gpt5ChatOptionsConverter
import com.embabel.agent.openai.StandardOpenAiOptionsConverter
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.LlmOptionsProperties
import com.embabel.common.util.ObjectProviders
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.ResourceLoader

/**
 * The catalog decides the transport; this is where that decision reaches production wiring.
 * The tests below drive the real registration path — no Spring context, no network — so a
 * regression in either direction shows up here rather than as a 404 in front of a user.
 *
 * @see <a href="https://github.com/embabel/embabel-agent/issues/1758">Issue 1758</a>
 */
class OpenAiModelsConfigRoutingTest {

    private val catalog = """
        models:
          - name: proModel
            model_id: gpt-5-pro
            api_format: RESPONSES
            special_handling:
              supports_temperature: false
              supports_top_p: false
              supports_frequency_penalty: false
              supports_presence_penalty: false
              uses_max_completion_tokens: true
          - name: chatModel
            model_id: gpt-4o-mini
    """.trimIndent()

    @Test
    fun `a RESPONSES model is wired onto the Responses adapter`() {
        assertInstanceOf(
            OpenAiResponsesChatModel::class.java,
            registeredLlms()["proModel"]?.chatModel,
            "A pro model left on the Chat Completions client 404s on every call",
        )
    }

    @Test
    fun `every other model keeps the Spring AI chat client`() {
        assertInstanceOf(
            OpenAiChatModel::class.java,
            registeredLlms()["chatModel"]?.chatModel,
            "Rerouting a working model onto the adapter changes its behaviour for no reason",
        )
    }

    /**
     * The transport switch sits next to the converter switch in `createOpenAiLlm`, and both read
     * the same model definition. Keeping this assertion alongside the transport one pins that
     * adding the first did not disturb the second.
     */
    @Test
    fun `special handling still selects the options converter independently of transport`() {
        val llms = registeredLlms()

        assertEquals(Gpt5ChatOptionsConverter, llms["proModel"]?.optionsConverter)
        assertEquals(StandardOpenAiOptionsConverter, llms["chatModel"]?.optionsConverter)
    }

    @Test
    fun `both models are registered under their catalog names`() {
        assertEquals(setOf("proModel", "chatModel"), registeredLlms().keys)
    }

    /**
     * The same registration path, driven by the catalog that actually ships rather than the
     * synthetic one above. That one proves the switch works; this proves the shipped entries reach
     * it. Neither needs a Spring context or the network, so both run on every build.
     */
    @Nested
    inner class ShippedCatalog {

        private val shipped = registeredLlms(OpenAiModelLoader())

        /**
         * Every entry, rather than a list of the names that happened to be new: the invariant is
         * that the catalog and the registry cannot drift, and it holds for models not yet written.
         */
        @Test
        fun `every catalog entry becomes a bean`() {
            val declared = OpenAiModelLoader().loadAutoConfigMetadata().effectiveModels().map { it.name }

            assertEquals(declared.toSet(), shipped.keys)
        }

        @Test
        fun `the pro tier is wired onto the Responses adapter`() {
            assertInstanceOf(
                OpenAiResponsesChatModel::class.java,
                shipped.values.single { it.name == "gpt-5.5-pro" }.chatModel,
                "gpt-5.5-pro left on the Chat Completions client fails on every call",
            )
        }
    }

    /** Runs the real bean-registration path and returns the LLM services it registered. */
    private fun registeredLlms(
        modelLoader: OpenAiModelLoader = inMemoryLoader(catalog),
    ): Map<String, SpringAiLlmService> {
        val registered = mutableMapOf<String, Any>()
        val beanFactory = mockk<ConfigurableBeanFactory>()
        every { beanFactory.registerSingleton(any(), any()) } answers {
            registered[firstArg()] = secondArg()
        }

        OpenAiModelsConfig(
            envBaseUrl = null,
            envApiKey = "test-key",
            envCompletionsPath = null,
            envEmbeddingsPath = null,
            observationRegistry = ObjectProviders.empty(),
            restClientBuilder = ObjectProviders.empty(),
            properties = OpenAiProperties(),
            llmOptionsProperties = LlmOptionsProperties(),
            configurableBeanFactory = beanFactory,
            modelLoader = modelLoader,
            webClientBuilder = ObjectProviders.empty(),
        ).openAiModelsInitializer()

        // The shipped catalog also registers embedding services, which are not LLMs.
        return registered
            .mapNotNull { (name, bean) -> (bean as? SpringAiLlmService)?.let { name to it } }
            .toMap()
    }

    private fun inMemoryLoader(yaml: String): OpenAiModelLoader {
        val resource = ByteArrayResource(yaml.toByteArray())
        val resourceLoader = object : ResourceLoader {
            override fun getResource(location: String) = resource
            override fun getClassLoader(): ClassLoader = javaClass.classLoader
        }
        return OpenAiModelLoader(resourceLoader, "memory:routing-test.yml")
    }
}
