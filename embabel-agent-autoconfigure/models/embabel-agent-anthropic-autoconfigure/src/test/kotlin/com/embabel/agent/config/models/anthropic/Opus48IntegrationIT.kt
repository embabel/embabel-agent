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
package com.embabel.agent.config.models.anthropic

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.models.AnthropicModels
import com.embabel.agent.autoconfigure.models.anthropic.AgentAnthropicAutoConfiguration
import com.embabel.agent.spi.LlmService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.opentest4j.TestAbortedException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles

/**
 * Spring configuration for the [Opus48IntegrationIT] context, scoped to the
 * `opus48-test` profile so it is only activated by that integration test.
 */
@Profile("opus48-test")
@ConfigurationPropertiesScan(basePackages = ["com.embabel.agent", "com.embabel.example"])
@ComponentScan(basePackages = ["com.embabel.agent", "com.embabel.example"])
class Opus48TestConfig

/**
 * Integration test for the Claude Opus 4.8 model definition added to `anthropic-models.yml`.
 *
 * Verifies two things end to end:
 *  1. The `claude_opus_48` model is loaded from YAML and registered as a Spring bean / [LlmService].
 *  2. A real call through [AnthropicModels.CLAUDE_OPUS_4_8] reaches the Anthropic API and responds.
 *
 * The live API test requires a valid `ANTHROPIC_API_KEY`; if the configured account lacks access
 * to Opus 4.8 the call is reported as aborted (skipped) rather than failed.
 */
@SpringBootTest(
    properties = ["embabel.models.default-llm=claude-opus-4-8", "embabel.agent.platform.models.anthropic.max-attempts=1", "spring.main.allow-bean-definition-overriding=true"]
)
@ActiveProfiles("opus48-test")
@Import(Opus48TestConfig::class, AgentAnthropicAutoConfiguration::class)
class Opus48IntegrationIT(
    @param:Autowired private val ai: Ai,
    @param:Autowired private val llms: List<LlmService<*>>,
    @param:Autowired private val applicationContext: ApplicationContext,
) {

    /**
     * Asserts the `claude_opus_48` model defined in YAML is registered as a Spring bean
     * and exposed as an [LlmService].
     */
    @Test
    fun `registers claude_opus_48 bean`() {

        // Context loads the model
        assertTrue(applicationContext.containsBean("claude_opus_48"), "Expected claude_opus_48 bean to be registered")

        // LLMs has the model
        val llm = findLlm()
        assertTrue(llm != null, "Expected Claude Opus 4.8 LLM service to be registered")
    }

    /**
     * Calls the live Anthropic API through Opus 4.8 and asserts it responds, confirming the
     * model id is valid and the account has access. Aborts (skips) if access is denied.
     */
    @Test
    fun `calls the real Anthropic API`() {

        // Get the LLM - findability is in the `registers claude_opus_48 bean` test
        val llm = findLlm()

        // Verify against Anthropic API
        val response = try {

            ai.withLlm(AnthropicModels.CLAUDE_OPUS_4_8).generateText("Reply with exactly the word READY.").trim()
        } catch (ex: Exception) {

            if (isModelAccessError(ex)) {
                throw TestAbortedException("ANTHROPIC_API_KEY is set, but the configured Anthropic account does not have access to ${AnthropicModels.CLAUDE_OPUS_4_8}", ex)
            }
            throw ex
        }

        assertTrue(response.isNotBlank(), "Expected non-empty response from Claude Opus 4.8")
        assertTrue(response.contains("READY", ignoreCase = true), "Expected Claude Opus 4.8 to reply with READY, got: $response")
        assertEquals(AnthropicModels.CLAUDE_OPUS_4_8, llm?.name)
    }

    private fun findLlm(): LlmService<*>? {

        return llms.find { it.name == AnthropicModels.CLAUDE_OPUS_4_8 }
    }

    private fun isModelAccessError(ex: Exception): Boolean {
        val message = generateSequence<Throwable>(ex) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

        return message.contains("does not have access to model", ignoreCase = true) || message.contains("not_found_error", ignoreCase = true) || message.contains(
            "model_not_found", ignoreCase = true
        )
    }

    override fun toString(): String {
        return "Opus48IntegrationIT(ai=$ai, llms=$llms, applicationContext=$applicationContext)"
    }
}
