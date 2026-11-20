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
package com.embabel.agent.openai

import com.embabel.common.ai.model.PricingModel
import com.embabel.common.byok.InvalidApiKeyException
import com.openai.client.OpenAIClient
import com.openai.client.OpenAIClientAsync
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.support.GenericApplicationContext
import java.util.function.Supplier

class OpenAiCompatibleModelFactoryClientLifecycleTest {

    private class RecordingFactory(apiKey: String = "test-key") :
        OpenAiCompatibleModelFactory(baseUrl = null, apiKey = apiKey) {
        override fun createOpenAiClient(): OpenAIClient = mockk(relaxed = true)
        override fun createOpenAiClientAsync(): OpenAIClientAsync = mockk(relaxed = true)
        val sync get() = openAiClient
        val async get() = openAiClientAsync
    }

    @Test
    fun `closing the factory closes both clients`() {
        val factory = RecordingFactory()

        factory.close()

        verify(exactly = 1) { factory.sync.close() }
        verify(exactly = 1) { factory.async.close() }
    }

    @Test
    fun `the async client is closed even when closing the sync client fails`() {
        val factory = RecordingFactory()
        every { factory.sync.close() } throws IllegalStateException("already gone")

        factory.close()

        verify(exactly = 1) { factory.async.close() }
    }

    @Test
    fun `a factory registered as a bean is closed when the context shuts down`() {
        val factory = RecordingFactory()
        val context = GenericApplicationContext()
        context.registerBean(OpenAiCompatibleModelFactory::class.java, Supplier { factory })
        context.refresh()

        context.close()

        verify(exactly = 1) { factory.sync.close() }
        verify(exactly = 1) { factory.async.close() }
    }

    @Test
    fun `closing an owned chat service closes both clients exactly once`() {
        val factory = RecordingFactory()
        val service = factory.buildOwnedLlm {
            llm("test-model", PricingModel.ALL_YOU_CAN_EAT, "test-provider")
        } as AutoCloseable

        factory.close()
        verify(exactly = 0) { factory.sync.close() }
        verify(exactly = 0) { factory.async.close() }

        service.close()
        service.close()
        verify(exactly = 1) { factory.sync.close() }
        verify(exactly = 1) { factory.async.close() }
    }

    @Test
    fun `failed validation closes both clients of a temporary factory`() {
        val factory = RecordingFactory(apiKey = " ")

        assertThrows<InvalidApiKeyException> {
            factory.buildOwnedLlm {
                buildValidated("test-model", PricingModel.ALL_YOU_CAN_EAT, "test-provider", null)
            }
        }

        verify(exactly = 1) { factory.sync.close() }
        verify(exactly = 1) { factory.async.close() }
    }

    @Test
    fun `failed async construction closes the sync client`() {
        val sync = mockk<OpenAIClient>(relaxed = true)

        assertThrows<IllegalStateException> {
            object : OpenAiCompatibleModelFactory(baseUrl = null, apiKey = "test-key") {
                override fun createOpenAiClient(): OpenAIClient = sync
                override fun createOpenAiClientAsync(): OpenAIClientAsync = error("async construction failed")
            }
        }

        verify(exactly = 1) { sync.close() }
    }

    @Test
    fun `closing an owned embedding service closes both clients`() {
        val factory = RecordingFactory()
        val service = factory.buildOwnedEmbedding {
            openAiCompatibleEmbeddingService("test-model", "test-provider", configuredDimensions = 3)
        } as AutoCloseable

        factory.close()
        verify(exactly = 0) { factory.sync.close() }

        service.close()
        service.close()
        verify(exactly = 1) { factory.sync.close() }
        verify(exactly = 1) { factory.async.close() }
    }
}
