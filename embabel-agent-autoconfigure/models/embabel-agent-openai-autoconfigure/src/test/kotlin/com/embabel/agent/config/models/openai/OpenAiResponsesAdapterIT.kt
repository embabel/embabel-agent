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
import com.embabel.agent.config.models.openai.OpenAiIntegrationSupport.isModelAccessError
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
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

@Profile("openai-responses-adapter-test")
@ConfigurationPropertiesScan(basePackages = ["com.embabel.agent"])
@ComponentScan(basePackages = ["com.embabel.agent"])
class OpenAiResponsesAdapterTestConfig

/** Facts the model cannot know, so a right answer proves the extraction rather than a guess. */
data class Shipment(
    val trackingId: String,
    val city: String,
    val delayDays: Int,
)

/** Returns a code nothing else could supply: the answer carries it only if the tool really ran. */
class VaultCodeTool {

    var invoked: Boolean = false
        private set

    @Tool(description = "Returns the current access code for a named vault")
    fun accessCode(vaultName: String): String {
        invoked = true
        return "ZX-9917"
    }
}

/**
 * Exercises [OpenAiResponsesChatModel]'s request mapping against the live endpoint.
 *
 * The unit tests assert what the adapter *builds*; the SDK is mocked, so a schema OpenAI would
 * reject or a `call_id` it would not pair still passes them. These two paths are where a Chat
 * Completions payload and a Responses one differ most — structured output moves from
 * `response_format` to `text.format` and gains a mandatory name, tools move from `tools[].function`
 * to a flat `FunctionTool` — so they are the two worth the price of a real call.
 *
 * Runs against [OpenAiModels.GPT_5_PRO]: one model is enough, the transport code is shared, and
 * this is the model the adapter was written for.
 *
 * @see <a href="https://github.com/embabel/embabel-agent/issues/1758">Issue 1758</a>
 */
@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gpt-4o-mini",
        "embabel.agent.platform.models.openai.max-attempts=1",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("openai-responses-adapter-test")
@Import(OpenAiResponsesAdapterTestConfig::class, AgentOpenAiAutoConfiguration::class)
@EnabledIfEnvironmentVariable(
    named = "OPENAI_API_KEY",
    matches = ".+",
    disabledReason = "Integration test requires OPENAI_API_KEY",
)
class OpenAiResponsesAdapterIT(
    @param:Autowired private val ai: Ai,
) {

    /**
     * The platform default is 60s, which a pro model exceeds on a two-turn tool loop: observed at
     * 108s. Left at the default the call times out and succeeds only on retry, running the loop —
     * and billing the reasoning — twice over.
     */
    private val proModel = LlmOptions.withModel(OpenAiModels.GPT_5_PRO)
        .withTimeout(Duration.ofMinutes(5))

    /**
     * Native structured output reaches the adapter as a Chat Completions `response_format` and is
     * rewritten under `text.format`, with a name derived on the way. A schema OpenAI refuses fails
     * the whole call, so this proves the endpoint accepts what the adapter builds.
     *
     * It cannot prove *which* path produced the object. Embabel also injects the schema into the
     * prompt, so a broken native path would still bind here. The rewrite itself is pinned in
     * `OpenAiResponsesChatModelTest`, and the catalog's declaration of it in `OpenAiModelLoaderTest`.
     */
    @Test
    fun `structured output binds over the Responses transport`() {
        val shipment = call {
            ai.withLlm(proModel).createObject(
                "Extract the shipment. Tracking XY-4423 is held in Lyon and is 3 days late.",
                Shipment::class.java,
            )
        }

        assertEquals("XY-4423", shipment.trackingId)
        assertEquals(3, shipment.delayDays)
        assertTrue(shipment.city.contains("Lyon", ignoreCase = true), "Got city: ${shipment.city}")
    }

    /**
     * The full loop, which no mock reaches: the adapter sends the tool, OpenAI answers with a
     * function call, Embabel runs it and replays call and result on the next turn. The Responses
     * API pairs those by `call_id`, so a lost id leaves the model reissuing the same call until the
     * loop gives up.
     *
     * The prompt names the tool and orders the call rather than asking a question it happens to
     * answer. Merely asking left the model free to decline, which it did on one run in six: a
     * single iteration, no tool call, and a failure that looked like the transport rather than the
     * model exercising its judgement. Whether a model volunteers a tool is not what this test is
     * for.
     */
    @Test
    fun `a tool is called and its result reaches the answer`() {
        val tool = VaultCodeTool()

        val answer = call {
            ai.withLlm(proModel)
                .withToolObject(tool)
                .generateText(
                    "Call the accessCode tool for the vault named 'north', " +
                        "then reply with the code it returned and nothing else."
                )
        }

        assertTrue(tool.invoked, "The model never called the tool, so the answer cannot be grounded")
        assertTrue(
            answer.contains("ZX-9917"),
            "The tool result never made it back into the answer, got: $answer",
        )
    }

    /** Same skip-on-no-entitlement contract as the other integration tests, for a non-text return. */
    private fun <T> call(block: () -> T): T =
        try {
            block()
        } catch (ex: Exception) {
            if (isModelAccessError(ex)) {
                throw TestAbortedException(
                    "OPENAI_API_KEY is set, but the configured OpenAI project has no access to " +
                        OpenAiModels.GPT_5_PRO,
                    ex,
                )
            }
            throw ex
        }
}
