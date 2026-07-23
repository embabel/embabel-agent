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

import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PricingModel
import com.embabel.agent.spi.LlmService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.opentest4j.TestAbortedException

/**
 * Live regression for the reported #1815 scenario: Gemini via the OpenAI-compatible path. This
 * is the cross-endpoint variant — the Gemini endpoint *rejects* an OpenAI model id, so if model
 * binding regressed (sending "gpt-5-mini"), the call would error. A successful call that reports
 * a gemini model back is therefore the regression guard, on both the blocking and streaming paths.
 *
 * Requires GEMINI_API_KEY. Skipped (aborted) if the key lacks access to the chosen model.
 */
class GeminiServedModelBindingIT {

    private val geminiBaseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"
    private val model = GoogleGenAiModels.GEMINI_2_5_FLASH

    private fun geminiLlm(): LlmService<*> =
        OpenAiCompatibleModelFactory(apiKey = System.getenv("GEMINI_API_KEY"), baseUrl = geminiBaseUrl)
            .openAiCompatibleLlm(
                model = model,
                pricingModel = PricingModel.ALL_YOU_CAN_EAT,
                provider = GoogleGenAiModels.PROVIDER,
                knowledgeCutoffDate = null,
            )

    @Test
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    fun `real Gemini serves the selected model over the OpenAI-compatible endpoint`() {
        // Execute
        val response = try {
            geminiLlm().createMessageSender(LlmOptions())
                .call(listOf(UserMessage("Reply with exactly the word READY.")), emptyList())
        } catch (ex: Exception) {
            if (isModelAccessError(ex)) {
                throw TestAbortedException("GEMINI_API_KEY is set but lacks access to $model", ex)
            }
            throw ex
        }

        // Verify: a successful call proves the gemini model (not gpt-5-mini) reached the endpoint.
        assertThat(response.textContent).isNotBlank()
        assertThat(response.model)
            .withFailMessage("Served model was '%s', expected a gemini model", response.model)
            .isNotNull()
            .contains("gemini")
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+")
    fun `real Gemini streaming reaches the endpoint with the selected model`() {
        // Execute
        val chunks = try {
            geminiLlm().createMessageStreamer(LlmOptions())
                .stream(listOf(UserMessage("Reply with exactly the word READY.")), emptyList(), emptyList())
                .collectList()
                .block()
        } catch (ex: Exception) {
            if (isModelAccessError(ex)) {
                throw TestAbortedException("GEMINI_API_KEY is set but lacks access to $model", ex)
            }
            throw ex
        }

        // Verify: streamed content proves the streaming path bound the gemini model (a wrong model
        // id would be rejected by the Gemini endpoint before any content streamed).
        assertThat(chunks).isNotNull()
        assertThat(chunks!!.joinToString("")).isNotBlank()
    }
}
