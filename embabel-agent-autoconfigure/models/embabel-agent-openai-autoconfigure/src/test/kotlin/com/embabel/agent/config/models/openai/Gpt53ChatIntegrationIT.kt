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

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration
import com.embabel.agent.spi.LlmService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.opentest4j.TestAbortedException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles

@Profile("gpt53-chat-test")
@ConfigurationPropertiesScan(
    basePackages = [
        "com.embabel.agent",
        "com.embabel.example",
    ]
)
@ComponentScan(
    basePackages = [
        "com.embabel.agent",
        "com.embabel.example",
    ]
)
class Gpt53ChatTestConfig

@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gpt-5.3-chat-latest",
        "embabel.agent.platform.models.openai.max-attempts=1",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("gpt53-chat-test")
@Import(Gpt53ChatTestConfig::class, AgentOpenAiAutoConfiguration::class)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+", disabledReason = "Integration test requires OPENAI_API_KEY")
class Gpt53ChatIntegrationIT(
    @param:Autowired private val ai: Ai,
    @param:Autowired private val llms: List<LlmService<*>>,
    @param:Autowired private val applicationContext: ApplicationContext,
) {

    @Test
    fun `registers gpt53chat bean`() {

        // Context loads the model
        assertTrue(applicationContext.containsBean("gpt53chat"), "Expected gpt53chat bean to be registered")

        // LLMs has the model
        val llm = findLlm()
        assertTrue(llm != null, "Expected GPT-5.3 Chat LLM service to be registered")
    }

    @Test
    fun `calls the real OpenAI API`() {

        // Get the LLM - findability is in the `registers gpt53chat bean` test
        val llm = findLlm()

        // Verify against OpenAI API
        val response = try {

            ai.withLlm(OpenAiModels.GPT_53_CHAT_LATEST).generateText("Reply with exactly the word READY.").trim()
        } catch (ex: Exception) {

            if (isModelAccessError(ex)) {
                throw TestAbortedException("OPENAI_API_KEY is set, but the configured OpenAI project does not have access to ${OpenAiModels.GPT_53_CHAT_LATEST}", ex)
            }
            throw ex
        }

        assertTrue(response.isNotBlank(), "Expected non-empty response from GPT-5.3 Chat")
        assertTrue(response.contains("READY", ignoreCase = true), "Expected GPT-5.3 Chat to reply with READY, got: $response")
        assertEquals(OpenAiModels.GPT_53_CHAT_LATEST, llm?.name)
    }

    private fun findLlm(): LlmService<*>? {

        return llms.find { it.name == OpenAiModels.GPT_53_CHAT_LATEST }
    }

    private fun isModelAccessError(ex: Exception): Boolean {
        val message = generateSequence<Throwable>(ex) { it.cause }.mapNotNull { it.message }.joinToString(" | ")

        return message.contains("does not have access to model", ignoreCase = true) || message.contains("model_not_found", ignoreCase = true)
    }

    override fun toString(): String {
        return "Gpt53ChatIntegrationIT(ai=$ai, llms=$llms, applicationContext=$applicationContext)"
    }
}
