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

@Profile("gpt5-sampling-params-test")
@ConfigurationPropertiesScan(basePackages = ["com.embabel.agent"])
@ComponentScan(basePackages = ["com.embabel.agent"])
class Gpt5SamplingParamsTestConfig

/**
 * Exercises GPT-5 models with sampling parameters set, against the real API.
 *
 * The token limit was not the only field the family refuses. Most GPT-5 models reject the sampling
 * parameters too — as a block, each for its presence alone:
 *
 * `400 Unsupported parameter: 'top_p' is not supported with this model.`
 *
 * and the converter used to forward `top_p` and both penalties to every one of them. Nothing
 * noticed, because nothing called a GPT-5 model *with* a sampling parameter set.
 *
 * The refusal is per model rather than per family — gpt-5.1, gpt-5.2 and the gpt-5.4 tiers do
 * accept these parameters — so both sides are called here: the strict models must stop refusing,
 * and the permissive ones must keep answering now that the parameters are dropped for the family as
 * a whole. The cheapest model on each side is used: these tests assert that a request is accepted,
 * and the answer's content is beside the point.
 */
@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gpt-4o-mini",
        "embabel.agent.platform.models.openai.max-attempts=1",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("gpt5-sampling-params-test")
@Import(Gpt5SamplingParamsTestConfig::class, AgentOpenAiAutoConfiguration::class)
@EnabledIfEnvironmentVariable(
    named = "OPENAI_API_KEY",
    matches = ".+",
    disabledReason = "Integration test requires OPENAI_API_KEY",
)
class Gpt5SamplingParamsIT(
    @param:Autowired private val ai: Ai,
) {

    @Test
    fun `a GPT-5 model that refuses sampling parameters answers once they are dropped`() {
        val response = replyWithSamplingParameters(OpenAiModels.GPT_5_NANO)

        assertTrue(response.contains("READY", ignoreCase = true), "Got: $response")
    }

    @Test
    fun `a GPT-5 model that accepts them is unharmed by their absence`() {
        val response = replyWithSamplingParameters(OpenAiModels.GPT_54_NANO)

        assertTrue(response.contains("READY", ignoreCase = true), "Got: $response")
    }

    /**
     * Every sampling parameter at once, each with a value the strict models refuse: a converter that
     * lets a single one through fails here rather than in front of a user.
     */
    private fun replyWithSamplingParameters(model: String): String =
        sayReady(
            ai,
            model,
            LlmOptions.withModel(model)
                .withTemperature(0.5)
                .withTopP(0.2)
                .withPresencePenalty(0.6)
                .withFrequencyPenalty(0.4)
                // Reasoning tokens are spent from this budget too, so a limit sized for the answer
                // alone comes back empty — see Gpt5MaxTokensIT.
                .withMaxTokens(8192),
        )
}
