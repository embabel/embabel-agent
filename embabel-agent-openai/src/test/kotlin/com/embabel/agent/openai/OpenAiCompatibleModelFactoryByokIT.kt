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

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * IT tests that validate BYOK endpoints against real provider APIs.
 * Each test requires the corresponding API key in the environment.
 */
class OpenAiCompatibleModelFactoryByokIT {

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    fun `openAi buildValidated succeeds with valid key`() {
        val service = OpenAiCompatibleModelFactory.openAi(System.getenv("OPENAI_API_KEY"))
            .buildValidated()
        assertNotNull(service)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
    fun `deepSeek buildValidated succeeds with valid key`() {
        val service = OpenAiCompatibleModelFactory.deepSeek(System.getenv("DEEPSEEK_API_KEY"))
            .buildValidated()
        assertNotNull(service)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "MISTRAL_API_KEY", matches = ".+")
    fun `mistral buildValidated succeeds with valid key`() {
        val service = OpenAiCompatibleModelFactory.mistral(System.getenv("MISTRAL_API_KEY"))
            .buildValidated()
        assertNotNull(service)
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GOOGLE_GENAI_API_KEY", matches = ".+")
    fun `gemini buildValidated succeeds with valid key`() {
        val service = OpenAiCompatibleModelFactory.gemini(System.getenv("GOOGLE_GENAI_API_KEY"))
            .buildValidated()
        assertNotNull(service)
    }
}
