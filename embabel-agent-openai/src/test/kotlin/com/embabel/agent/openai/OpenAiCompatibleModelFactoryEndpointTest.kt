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
import com.embabel.agent.api.models.OrcaRouterModels
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class OpenAiCompatibleModelFactoryEndpointTest {

    @Nested
    inner class KnownProviders {

        @Test
        fun `OpenAI itself has no base url, meaning the SDK default`() {
            val endpoint = OpenAiCompatibleModelFactory.endpointFor(OpenAiModels.PROVIDER)
            assertEquals(OpenAiModels.PROVIDER, endpoint?.provider)
            assertNull(endpoint?.baseUrl)
        }

        @Test
        fun `every provider this module supports has an endpoint`() {
            val expected = mapOf(
                DeepSeekModels.PROVIDER to "https://api.deepseek.com",
                MistralAiModels.PROVIDER to "https://api.mistral.ai/v1",
                GoogleGenAiModels.PROVIDER to "https://generativelanguage.googleapis.com/v1beta/openai",
                AtlasCloudModels.PROVIDER to "https://api.atlascloud.ai/v1",
                OrcaRouterModels.PROVIDER to "https://api.orcarouter.ai/v1",
            )
            expected.forEach { (provider, baseUrl) ->
                assertEquals(baseUrl, OpenAiCompatibleModelFactory.endpointFor(provider)?.baseUrl, provider)
            }
        }
    }

    @Nested
    inner class Matching {

        @Test
        fun `provider names are matched case-insensitively`() {
            assertEquals(
                MistralAiModels.PROVIDER,
                OpenAiCompatibleModelFactory.endpointFor("mistral ai")?.provider,
            )
        }

        @Test
        fun `the canonical name is returned, not the caller's spelling`() {
            // What a credential carries is whatever the application stored; what the built service
            // reports has to be the constant, or cost and metadata lookups key on two spellings.
            assertEquals(
                DeepSeekModels.PROVIDER,
                OpenAiCompatibleModelFactory.endpointFor("DEEPSEEK")?.provider,
            )
        }

        @Test
        fun `surrounding whitespace does not defeat the lookup`() {
            assertEquals(
                OpenAiModels.PROVIDER,
                OpenAiCompatibleModelFactory.endpointFor("  OpenAI  ")?.provider,
            )
        }

        @Test
        fun `an unhandled provider returns null rather than a guessed endpoint`() {
            assertNull(OpenAiCompatibleModelFactory.endpointFor("Anthropic"))
            assertNull(OpenAiCompatibleModelFactory.endpointFor("SomeProviderNobodyShips"))
            assertNull(OpenAiCompatibleModelFactory.endpointFor(""))
        }
    }
}
