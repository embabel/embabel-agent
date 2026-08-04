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
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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

    /** Runs the real bean-registration path and returns what it registered. */
    private fun registeredLlms(): Map<String, SpringAiLlmService> {
        val names = mutableListOf<String>()
        val beans = mutableListOf<Any>()
        val beanFactory = mockk<ConfigurableBeanFactory>()
        val name = slot<String>()
        val bean = slot<Any>()
        every { beanFactory.registerSingleton(capture(name), capture(bean)) } answers {
            names += name.captured
            beans += bean.captured
        }

        val resource = ByteArrayResource(catalog.toByteArray())
        val resourceLoader = object : ResourceLoader {
            override fun getResource(location: String) = resource
            override fun getClassLoader(): ClassLoader = javaClass.classLoader
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
            modelLoader = OpenAiModelLoader(resourceLoader, "memory:routing-test.yml"),
            webClientBuilder = ObjectProviders.empty(),
        ).openAiModelsInitializer()

        return names.zip(beans).toMap()
            .mapValues { (_, service) -> service as SpringAiLlmService }
    }
}
