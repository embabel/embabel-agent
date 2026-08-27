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
import com.embabel.agent.config.models.openai.OpenAiIntegrationSupport.sayReady
import com.embabel.agent.spi.LlmService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles

@Profile("gpt5-pro-test")
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
class Gpt5ProTestConfig

/**
 * Exercises a model that OpenAI serves only over the Responses API, against the real endpoint.
 *
 * This is the test that was missing: the `*-pro` models were declared in the catalog and the build
 * stayed green, because nothing ever called them. Every real call returned HTTP 400 until
 * [OpenAiResponsesChatModel] was routed in.
 *
 * @see <a href="https://github.com/embabel/embabel-agent/issues/1758">Issue 1758</a>
 */
@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gpt-5-pro",
        "embabel.agent.platform.models.openai.max-attempts=1",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("gpt5-pro-test")
@Import(Gpt5ProTestConfig::class, AgentOpenAiAutoConfiguration::class)
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+", disabledReason = "Integration test requires OPENAI_API_KEY")
class Gpt5ProResponsesApiIT(
    @param:Autowired private val ai: Ai,
    @param:Autowired private val llms: List<LlmService<*>>,
    @param:Autowired private val applicationContext: ApplicationContext,
) {

    @Test
    fun `registers gpt5pro bean`() {
        assertTrue(applicationContext.containsBean("gpt5pro"), "Expected gpt5pro bean to be registered")
        assertTrue(findLlm() != null, "Expected GPT-5 Pro LLM service to be registered")
    }

    @Test
    fun `calls the real Responses API`() {
        val response = sayReady(ai, OpenAiModels.GPT_5_PRO)

        assertTrue(response.isNotBlank(), "Expected non-empty response from GPT-5 Pro")
        assertTrue(response.contains("READY", ignoreCase = true), "Expected GPT-5 Pro to reply with READY, got: $response")
        assertEquals(OpenAiModels.GPT_5_PRO, findLlm()?.name)
    }

    private fun findLlm(): LlmService<*>? = llms.find { it.name == OpenAiModels.GPT_5_PRO }
}
