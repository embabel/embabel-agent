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
package com.embabel.agent.anthropic

import com.embabel.agent.api.models.AnthropicModels
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.opentest4j.TestAbortedException

/**
 * Live regression for the Anthropic same-provider silent variant of #1815: select Sonnet and
 * assert Anthropic reports a sonnet model back. If binding regressed, the request would run on
 * the AnthropicChatOptions default ("claude-haiku-4-5") and the served model would be a haiku.
 *
 * Requires ANTHROPIC_API_KEY. Skipped (aborted) if the key lacks access to the chosen model.
 */
class AnthropicServedModelBindingIT {

    @Test
    @EnabledIfEnvironmentVariable(named = "ANTHROPIC_API_KEY", matches = ".+")
    fun `real Anthropic serves the selected model, not the default`() {
        // Prepare
        val model = AnthropicModels.CLAUDE_SONNET_4_5
        val llm = AnthropicModelFactory(apiKey = System.getenv("ANTHROPIC_API_KEY")).build(model)

        // Execute
        val response = try {
            llm.createMessageSender(LlmOptions())
                .call(listOf(UserMessage("Reply with exactly the word READY.")), emptyList())
        } catch (ex: Exception) {
            if (isModelAccessError(ex)) {
                throw TestAbortedException("ANTHROPIC_API_KEY is set but lacks access to $model", ex)
            }
            throw ex
        }

        // Verify: Anthropic served the selected Sonnet, not the claude-haiku-4-5 default.
        assertThat(response.textContent).isNotBlank()
        assertThat(response.model)
            .withFailMessage("Served model was '%s', expected a sonnet model", response.model)
            .isNotNull()
            .contains("sonnet")
        assertThat(response.model).doesNotContain("haiku")
    }

    /** Broad check for a provider "no access to this model" error, so the IT aborts rather than fails. */
    private fun isModelAccessError(ex: Throwable): Boolean {
        val message = generateSequence<Throwable>(ex) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
            .lowercase()
        return listOf(
            "not_found",
            "does not exist",
            "not found",
            "permission",
            "invalid model",
            "unsupported model",
            "does not have access",
        ).any { message.contains(it) }
    }
}
