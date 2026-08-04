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
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles

@Profile("gpt5-max-tokens-test")
@ConfigurationPropertiesScan(basePackages = ["com.embabel.agent"])
@ComponentScan(basePackages = ["com.embabel.agent"])
class Gpt5MaxTokensTestConfig

/**
 * Exercises a GPT-5 model with a token limit set, against the real API.
 *
 * This is the test that was missing. Nothing ever called a GPT-5 model *with* a limit, so nothing
 * noticed that [com.embabel.agent.openai.Gpt5ChatOptionsConverter] was sending `max_tokens`, which
 * the whole family refuses:
 *
 * `400 Unsupported parameter: 'max_tokens' is not supported with this model.
 *  Use 'max_completion_tokens' instead.`
 *
 * Both transports are covered, because they read the limit from different option fields: gpt-5.4
 * over Chat Completions, gpt-5-pro over the Responses API.
 */
@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gpt-4o-mini",
        "embabel.agent.platform.models.openai.max-attempts=1",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("gpt5-max-tokens-test")
@Import(Gpt5MaxTokensTestConfig::class, AgentOpenAiAutoConfiguration::class)
@EnabledIfEnvironmentVariable(
    named = "OPENAI_API_KEY",
    matches = ".+",
    disabledReason = "Integration test requires OPENAI_API_KEY",
)
class Gpt5MaxTokensIT(
    @param:Autowired private val ai: Ai,
) {

    @Test
    fun `a GPT-5 model accepts a token limit over Chat Completions`() {
        assertTrue(replyWithLimit(OpenAiModels.GPT_54).isNotBlank())
    }

    @Test
    fun `a GPT-5 model accepts a token limit over the Responses API`() {
        assertTrue(replyWithLimit(OpenAiModels.GPT_5_PRO).isNotBlank())
    }

    private fun replyWithLimit(model: String): String =
        ai.withLlm(LlmOptions.withModel(model).withMaxTokens(2048))
            .generateText("Reply with exactly the word READY.")
            .trim()
}
