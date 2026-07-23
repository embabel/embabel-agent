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

import com.embabel.agent.api.models.OpenAiModels
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PricingModel
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.opentest4j.TestAbortedException

/**
 * Live regression for the same-provider silent variant of #1815: with real OpenAI, select a
 * non-default model and assert the provider reports that model back (via response metadata).
 * If model binding regressed, the request would silently run on the OpenAiChatOptions default
 * ("gpt-5-mini") and the served model would not match the selection.
 *
 * Requires OPENAI_API_KEY. Skipped (aborted) if the key lacks access to the chosen model.
 */
class OpenAiServedModelBindingIT {

    @Test
    @EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
    fun `real OpenAI serves the selected model, not the default`() {
        // Prepare
        val model = OpenAiModels.GPT_54
        val llm = OpenAiCompatibleModelFactory(apiKey = System.getenv("OPENAI_API_KEY"), baseUrl = null)
            .openAiCompatibleLlm(
                model = model,
                pricingModel = PricingModel.ALL_YOU_CAN_EAT,
                provider = OpenAiModels.PROVIDER,
                knowledgeCutoffDate = null,
            )

        // Execute
        val response = try {
            llm.createMessageSender(LlmOptions())
                .call(listOf(UserMessage("Reply with exactly the word READY.")), emptyList())
        } catch (ex: Exception) {
            if (isModelAccessError(ex)) {
                throw TestAbortedException("OPENAI_API_KEY is set but lacks access to $model", ex)
            }
            throw ex
        }

        // Verify: the provider served the selected model family, not the gpt-5-mini default.
        assertThat(response.textContent).isNotBlank()
        assertThat(response.model)
            .withFailMessage("Served model was '%s', expected to contain '%s'", response.model, model)
            .isNotNull()
            .contains(model)
        assertThat(response.model).doesNotContain("mini")
    }
}

/** Broad check for a provider "you don't have access to this model" error, so ITs abort rather than fail. */
internal fun isModelAccessError(ex: Throwable): Boolean {
    val message = generateSequence<Throwable>(ex) { it.cause }
        .mapNotNull { it.message }
        .joinToString(" | ")
        .lowercase()
    return listOf(
        "does not have access to model",
        "model_not_found",
        "does not exist",
        "not found",
        "permission",
        "invalid model",
        "unsupported model",
    ).any { message.contains(it) }
}
