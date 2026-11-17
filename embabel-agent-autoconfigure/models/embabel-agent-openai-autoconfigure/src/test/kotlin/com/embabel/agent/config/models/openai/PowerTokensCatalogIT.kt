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
import com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration
import com.embabel.agent.config.models.openai.OpenAiIntegrationSupport.sayReady
import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PricingModel
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Duration

/**
 * Scans Embabel for the PowerTokens catalog IT.
 *
 * Excludes [GuardRailConfiguration] from [LLMOpenAiGuardRailsIntegrationIT]: that config has no
 * `@Profile`, so a bare package scan would load it and make it read shell `OPENAI_API_KEY`,
 * failing this IT for reasons that have nothing to do with PowerTokens.
 */
@Profile("powertokens-catalog-test")
@ConfigurationPropertiesScan(basePackages = ["com.embabel.agent"])
@ComponentScan(
    basePackages = ["com.embabel.agent"],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [GuardRailConfiguration::class],
        )
    ],
)
class PowerTokensCatalogTestConfig

/**
 * Registers the PowerTokens smoke models against the OpenAI-compatible client that
 * [OpenAiModelsConfig] already pointed at PowerTokens via OPENAI_BASE_URL / OPENAI_API_KEY.
 *
 * The shipped OpenAI catalog still loads (same autoconfig), but those ids are not what
 * PowerTokens serves; chat and tool tests select the beans registered here by model id.
 */
@Profile("powertokens-catalog-test")
@Configuration
class PowerTokensSmokeModelsConfig(
    @param:Value("\${OPENAI_API_KEY}") private val apiKey: String,
    private val configurableBeanFactory: ConfigurableBeanFactory,
) {

    @Bean
    fun powerTokensSmokeModels(): ProviderInitialization {
        val factory = OpenAiCompatibleModelFactory(
            baseUrl = PowerTokensCatalogIT.BASE_URL,
            apiKey = apiKey,
        )
        val registered = PowerTokensCatalogIT.smokeModels().map { modelId ->
            val llm: LlmService<*> = factory.openAiCompatibleLlm(
                model = modelId,
                pricingModel = PricingModel.ALL_YOU_CAN_EAT,
                provider = "PowerTokens",
                knowledgeCutoffDate = null,
            )
            // Bean name is local; ModelProvider resolves by LlmService.name (== modelId).
            configurableBeanFactory.registerSingleton("powertokens-$modelId", llm)
            RegisteredModel(beanName = "powertokens-$modelId", modelId = modelId)
        }
        return ProviderInitialization(
            provider = "PowerTokens",
            registeredLlms = registered,
            registeredEmbeddings = emptyList(),
        )
    }
}

/**
 * Live tests for PowerTokens' OpenAI-compatible gateway through Embabel [Ai].
 *
 * Chat and tool calls use the same shape as [OpenAiCatalogIT]. Model-list and invalid-key
 * coverage live in [PowerTokensModelsListIT] (no Spring), so they stay fast and independent
 * of this context.
 *
 * To run these tests you need a PowerTokens account (sign-up / sign-in is required; a key
 * cannot be created anonymously):
 * 1. Open https://powertokens.ai and sign in (Google, GitHub, or email).
 * 2. Dashboard → API Keys → Create new key
 *    (guide: https://docs.powertokens.ai/en/guides/api-key-and-model-call).
 * 3. export POWERTOKENS_API_KEY=<that key>
 * The Spring context is pointed at PowerTokens in code via [DynamicPropertySource]
 * (`OPENAI_BASE_URL` and `OPENAI_API_KEY` from `POWERTOKENS_API_KEY`); no shell export of
 * those two is required.
 * An existing key with chat access also works.
 * Tests that need a valid key are skipped when POWERTOKENS_API_KEY is not set.
 *
 * @see <a href="https://github.com/embabel/embabel-agent/issues/2066">Issue 2066</a>
 */
@SpringBootTest(
    properties = [
        // One of the smoke models; the bean is registered by PowerTokensSmokeModelsConfig.
        "embabel.models.default-llm=deepseek-v3-2-251201",
        "embabel.agent.platform.models.openai.max-attempts=1",
        "embabel.agent.platform.llm-operations.data-binding.max-attempts=1",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("powertokens-catalog-test")
@Import(
    PowerTokensCatalogTestConfig::class,
    PowerTokensSmokeModelsConfig::class,
    AgentOpenAiAutoConfiguration::class,
)
class PowerTokensCatalogIT(
    @param:Autowired private val ai: Ai,
) {

    companion object {
        const val BASE_URL = "https://api.powertokens.ai/v1"

        /** Models used by the chat and tool tests. These must be available through the API. */
        @JvmStatic
        fun smokeModels(): List<String> = listOf(
            "deepseek-v3-2-251201",
            "glm-5.2",
            "MiniMax-M3",
        )

        /**
         * Point the OpenAI autoconfig at PowerTokens. OPENAI_API_KEY is filled from
         * POWERTOKENS_API_KEY when present; a placeholder keeps the context loadable when the
         * key is absent (chat/tool tests then skip via EnabledIfEnvironmentVariable).
         */
        @JvmStatic
        @DynamicPropertySource
        fun powerTokensOpenAiProps(registry: DynamicPropertyRegistry) {
            registry.add("OPENAI_BASE_URL") { BASE_URL }
            registry.add("OPENAI_API_KEY") {
                System.getenv("POWERTOKENS_API_KEY") ?: "powertokens-test-placeholder"
            }
        }
    }

    /**
     * glm-5.2 counts reasoning toward the completion budget; 256 leaves room for a short reply.
     * Five minutes covers a two-turn tool loop on slower models.
     */
    private fun optionsFor(model: String) =
        LlmOptions.withModel(model)
            .withMaxTokens(256)
            .withTimeout(Duration.ofMinutes(5))

    /** Each smoke model answers a short prompt through Embabel [Ai]. */
    @ParameterizedTest(name = "{0} answers READY through Embabel Ai")
    @MethodSource("smokeModels")
    @EnabledIfEnvironmentVariable(
        named = "POWERTOKENS_API_KEY",
        matches = ".+",
        disabledReason = "Integration test requires POWERTOKENS_API_KEY",
    )
    fun `each smoke model replies READY through Embabel Ai`(model: String) {
        val response = sayReady(ai, model, optionsFor(model))

        assertTrue(
            response.contains("READY", ignoreCase = true),
            "Expected $model to reply READY, got: $response",
        )
    }

    /** Each smoke model calls a bound tool and returns its result through Embabel [Ai]. */
    @ParameterizedTest(name = "{0} answers a request carrying function tools")
    @MethodSource("smokeModels")
    @EnabledIfEnvironmentVariable(
        named = "POWERTOKENS_API_KEY",
        matches = ".+",
        disabledReason = "Integration test requires POWERTOKENS_API_KEY",
    )
    fun `each smoke model answers a tool-carrying request through Embabel Ai`(model: String) {
        val tool = BadgeCodeTool()

        val answer = ai.withLlm(optionsFor(model).withMaxTokens(512))
            .withToolObject(tool)
            .generateText(
                "Call the badgeCode tool for the door named 'north', " +
                    "then reply with the code it returned and nothing else."
            )

        assertTrue(
            tool.invoked,
            "$model never called the tool, so the answer cannot be grounded, got: '$answer'",
        )
        assertTrue(
            answer.contains("QP-4471"),
            "The tool result never made it back into the answer from $model, got: $answer",
        )
    }
}
