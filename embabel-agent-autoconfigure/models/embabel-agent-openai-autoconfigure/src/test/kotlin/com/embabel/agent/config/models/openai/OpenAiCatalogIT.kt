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
import com.embabel.agent.config.models.openai.OpenAiIntegrationSupport.isModelAccessError
import com.embabel.agent.config.models.openai.OpenAiIntegrationSupport.sayReady
import com.embabel.common.ai.model.LlmOptions
import com.openai.client.okhttp.OpenAIOkHttpClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.opentest4j.TestAbortedException
import org.springframework.ai.tool.annotation.Tool
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import java.time.Duration

@Profile("openai-catalog-test")
@ConfigurationPropertiesScan(basePackages = ["com.embabel.agent"])
@ComponentScan(basePackages = ["com.embabel.agent"])
class OpenAiCatalogTestConfig

/** Returns a code nothing else could supply: the answer carries it only if the tool really ran. */
class BadgeCodeTool {

    var invoked: Boolean = false
        private set

    @Tool(description = "Returns the current badge code for a named door")
    fun badgeCode(doorName: String): String {
        invoked = true
        return "QP-4471"
    }
}

/**
 * Holds the shipped catalog against the real OpenAI API.
 *
 * The unit tests prove the catalog is *read* as written; nothing until here proves it describes
 * models that exist. That gap is how `*-pro` shipped broken — declared, never called, build green.
 * Every entry added since carries the same risk, so the claims a catalog entry makes are checked
 * against the provider: that the id resolves, that the declared transport works, and that the
 * transport still works once the request carries tools.
 *
 * The parameter lists are derived from the catalog rather than written out, so a model added to
 * the YAML is covered without anyone remembering to add it here. A hand-written list is the same
 * mechanism as no list at all: `gpt-5.6-luna` was in one, and its tool calling was still broken.
 *
 * @see <a href="https://github.com/embabel/embabel-agent/issues/1758">Issue 1758</a>
 */
@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gpt-4o-mini",
        // One attempt: a rejected payload is not a transient failure, and retrying it only buries
        // the provider's message under a "Failed after 3 attempt(s)".
        "embabel.agent.platform.models.openai.max-attempts=1",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("openai-catalog-test")
@Import(OpenAiCatalogTestConfig::class, AgentOpenAiAutoConfiguration::class)
@EnabledIfEnvironmentVariable(
    named = "OPENAI_API_KEY",
    matches = ".+",
    disabledReason = "Integration test requires OPENAI_API_KEY",
)
class OpenAiCatalogIT(
    @param:Autowired private val ai: Ai,
) {

    companion object {

        /**
         * Every chat model the catalog ships, so coverage tracks the YAML instead of a copy of it.
         */
        @JvmStatic
        fun catalogModels(): List<String> =
            OpenAiModelLoader().loadAutoConfigMetadata().effectiveModels().map { it.modelId }
    }

    /**
     * The platform default of 60s is exceeded by the pro tiers on a two-turn tool loop — observed
     * at 108s in `OpenAiResponsesAdapterIT`. Left at the default those calls time out and succeed
     * only on retry, running the loop, and billing the reasoning, twice over.
     */
    private fun optionsFor(model: String) =
        LlmOptions.withModel(model).withTimeout(Duration.ofMinutes(5))

    /**
     * One `GET /v1/models` covers the whole catalog, so a typo in any entry is caught without
     * calling that model: the cheapest coverage available for the thing most easily got wrong.
     *
     * Deliberately built on a bare client rather than the configured one. The question here is what
     * OpenAI serves, not what a custom base URL would answer.
     */
    @Test
    fun `every catalog model id is one OpenAI serves`() {
        val definitions = OpenAiModelLoader().loadAutoConfigMetadata()
        val declared = definitions.effectiveModels().map { it.modelId } +
            definitions.embeddingModels.map { it.modelId }

        val client = OpenAIOkHttpClient.builder()
            .apiKey(System.getenv("OPENAI_API_KEY"))
            .build()
        val served = client.models().list().autoPager().map { it.id() }.toSet()

        val unknown = declared.filterNot { it in served }

        assertTrue(
            unknown.isEmpty(),
            "Catalog ids OpenAI does not serve for this project. Either a typo, a retired model, " +
                "or one this project is not entitled to: $unknown",
        )
    }

    /**
     * A wrong transport is a hard failure rather than a wrong answer, so a reply at all is the
     * assertion that matters: `gpt-5.5-pro` on Chat Completions never returns one.
     *
     * That the entries register and route correctly is unit-tested in `OpenAiModelsConfigRoutingTest`,
     * which needs neither a key nor a network and therefore runs on every build.
     */
    @ParameterizedTest(name = "{0} answers over its declared transport")
    @MethodSource("catalogModels")
    fun `each catalog model replies over the transport the catalog declares`(model: String) {
        val response = sayReady(ai, model, optionsFor(model))

        assertTrue(
            response.contains("READY", ignoreCase = true),
            "Expected $model to reply READY, got: $response",
        )
    }

    /**
     * The same models again, with a tool bound. This is a separate claim from the one above, and
     * the reason this test exists: a payload carrying `tools` can be refused by a model that
     * answers a plain prompt perfectly well over the same endpoint. Reported against the GPT-5.6
     * tiers as
     *
     *     400: Function tools with reasoning_effort are not supported for gpt-5.6-luna in
     *     /v1/chat/completions. To use function tools, use /v1/responses or set reasoning_effort
     *     to 'none'.
     *
     * Tool calling is what every agent run does, so a model that fails here is unusable for the
     * thing this project is for, however well it answers a bare prompt.
     *
     * The prompt names the tool and orders the call rather than asking a question the model happens
     * to answer with it. Whether a model volunteers a tool is its own judgement and not what this
     * test is for; what is under test is whether the request is accepted and the result returned.
     */
    @ParameterizedTest(name = "{0} answers a request carrying function tools")
    @MethodSource("catalogModels")
    fun `each catalog model answers a tool-carrying request over its declared transport`(model: String) {
        val tool = BadgeCodeTool()

        val answer = call(model) {
            ai.withLlm(optionsFor(model))
                .withToolObject(tool)
                .generateText(
                    "Call the badgeCode tool for the door named 'north', " +
                        "then reply with the code it returned and nothing else."
                )
        }

        assertTrue(tool.invoked, "$model never called the tool, so the answer cannot be grounded")
        assertTrue(
            answer.contains("QP-4471"),
            "The tool result never made it back into the answer from $model, got: $answer",
        )
    }

    /** Same skip-on-no-entitlement contract as [sayReady], for a call that is not a bare prompt. */
    private fun <T> call(model: String, block: () -> T): T =
        try {
            block()
        } catch (ex: Exception) {
            if (isModelAccessError(ex)) {
                throw TestAbortedException(
                    "OPENAI_API_KEY is set, but the configured OpenAI project has no access to $model",
                    ex,
                )
            }
            throw ex
        }
}
