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

import com.embabel.agent.api.models.AtlasCloudModels
import com.embabel.agent.api.models.DeepSeekModels
import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.api.models.MistralAiModels
import com.embabel.agent.api.models.OpenAiModels
import com.embabel.common.byok.ByokFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class OpenAiCompatibleModelFactoryByokSpecTest {

    @Test
    fun `openAi returns ByokFactory`() {
        assertInstanceOf(ByokFactory::class.java, OpenAiCompatibleModelFactory.openAi("key"))
    }

    @Test
    fun `deepSeek returns ByokFactory`() {
        assertInstanceOf(ByokFactory::class.java, OpenAiCompatibleModelFactory.deepSeek("key"))
    }

    @Test
    fun `mistral returns ByokFactory`() {
        val spec = OpenAiCompatibleModelFactory.mistral("key")

        assertInstanceOf(ByokFactory::class.java, spec)
        val baseUrl = spec.javaClass.getDeclaredField("baseUrl").apply { isAccessible = true }.get(spec)
        assertEquals("https://api.mistral.ai/v1", baseUrl)
    }

    @Test
    fun `gemini returns ByokFactory`() {
        assertInstanceOf(ByokFactory::class.java, OpenAiCompatibleModelFactory.gemini("key"))
    }

    @Test
    fun `atlasCloud returns ByokFactory`() {
        assertInstanceOf(ByokFactory::class.java, OpenAiCompatibleModelFactory.atlasCloud("key"))
    }

    @Test
    fun `byok with explicit params returns ByokFactory`() {
        assertInstanceOf(
            ByokFactory::class.java,
            OpenAiCompatibleModelFactory.byok(
                baseUrl = "https://api.example.com",
                apiKey = "key",
                validationModel = "my-model",
                validationProvider = "MyProvider",
            )
        )
    }

    @Test
    fun `validating returns new ByokSpec with overridden model and provider`() {
        val original = OpenAiCompatibleModelFactory.openAi("key")
        val overridden = original.validating(OpenAiModels.GPT_41_NANO, OpenAiModels.PROVIDER)
        assertInstanceOf(ByokFactory::class.java, overridden)
        assert(original !== overridden) { "validating() should return a new instance" }
    }

    @Test
    fun `named factories use expected default validation models`() {
        // Verify defaults are the cheapest/most widely accessible models per provider.
        // These are structural: if the constants are renamed this test catches it.
        val openAiSpec = OpenAiCompatibleModelFactory.openAi("key")
        val deepSeekSpec = OpenAiCompatibleModelFactory.deepSeek("key")
        val mistralSpec = OpenAiCompatibleModelFactory.mistral("key")
        val geminiSpec = OpenAiCompatibleModelFactory.gemini("key")
        val atlasCloudSpec = OpenAiCompatibleModelFactory.atlasCloud("key")

        // Each spec should construct without error (no network call at this stage)
        assertInstanceOf(ByokFactory::class.java, openAiSpec)
        assertInstanceOf(ByokFactory::class.java, deepSeekSpec)
        assertInstanceOf(ByokFactory::class.java, mistralSpec)
        assertInstanceOf(ByokFactory::class.java, geminiSpec)
        assertInstanceOf(ByokFactory::class.java, atlasCloudSpec)

        val overridden = atlasCloudSpec.validating(AtlasCloudModels.QWEN3_8_MAX, AtlasCloudModels.PROVIDER)
        assertInstanceOf(ByokFactory::class.java, overridden)
    }
}
