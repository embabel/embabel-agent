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

import com.fasterxml.jackson.annotation.JsonClassDescription
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.openai.client.okhttp.OpenAIOkHttpClient
import com.openai.errors.OpenAIServiceException
import com.openai.models.ChatModel
import com.openai.models.chat.completions.ChatCompletionCreateParams
import com.openai.models.chat.completions.ChatCompletionToolMessageParam
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.Collection

/**
 * Live tests for PowerTokens' OpenAI-compatible API.
 *
 * To run these tests, create an API key at https://powertokens.ai
 * (Dashboard → API Keys) and set POWERTOKENS_API_KEY to that key.
 * An existing key with chat access also works.
 * Tests that need a valid key are skipped when the variable is not set.
 *
 * Checks model availability, chat responses, and tool calls.
 *
 * @see <a href="https://github.com/embabel/embabel-agent/issues/2066">Issue 2066</a>
 */
class PowerTokensCatalogIT {

    companion object {
        const val BASE_URL = "https://api.powertokens.ai/v1"

        /** Models used by the chat and tool tests. These must be available through the API. */
        @JvmStatic
        fun smokeModels(): List<String> = listOf(
            "deepseek-v3-2-251201",
            "glm-5.2",
            "MiniMax-M3",
        )

        private fun client(apiKey: String) =
            OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .baseUrl(BASE_URL)
                .build()

        /** Not a key PowerTokens issued. */
        const val UNKNOWN_KEY = "not-a-real-key"
    }

    /** Check that all models used below are available to this key. */
    @Test
    @EnabledIfEnvironmentVariable(
        named = "POWERTOKENS_API_KEY",
        matches = ".+",
        disabledReason = "Integration test requires POWERTOKENS_API_KEY",
    )
    fun `every smoke model id is one PowerTokens serves`() {
        val client = client(System.getenv("POWERTOKENS_API_KEY"))
        // Fetch the model list once and check the smoke model IDs against it.
        val served = client.models().list().autoPager().map { it.id() }.toSet()

        val unknown = smokeModels().filterNot { it in served }

        assertTrue(
            unknown.isEmpty(),
            "Smoke ids PowerTokens does not serve for this key. Either a typo, a retired model, " +
                "or one this account is not entitled to: $unknown",
        )
    }

    /**
     * Check that each model can return a simple chat response.
     *
     * The completion limit is 256 because glm-5.2 also uses it for reasoning tokens.
     * A smaller limit can leave no tokens for the reply.
     */
    @ParameterizedTest(name = "{0} answers READY over chat completions")
    @MethodSource("smokeModels")
    @EnabledIfEnvironmentVariable(
        named = "POWERTOKENS_API_KEY",
        matches = ".+",
        disabledReason = "Integration test requires POWERTOKENS_API_KEY",
    )
    fun `each smoke model replies READY over chat completions`(model: String) {
        val client = client(System.getenv("POWERTOKENS_API_KEY"))
        val completion = client.chat().completions().create(
            ChatCompletionCreateParams.builder()
                .model(ChatModel.of(model))
                .maxCompletionTokens(256)
                .addUserMessage(OpenAiIntegrationSupport.READY_PROMPT)
                .build(),
        )

        val response = completion.choices().stream()
            .flatMap { it.message().content().stream() }
            .findFirst()
            .orElse("")
            .trim()

        assertTrue(
            response.contains("READY", ignoreCase = true),
            "Expected $model to reply READY, got: $response",
        )
    }

    /** Check that each model calls BadgeCode and uses its result in the reply. */
    @ParameterizedTest(name = "{0} answers a request carrying function tools")
    @MethodSource("smokeModels")
    @EnabledIfEnvironmentVariable(
        named = "POWERTOKENS_API_KEY",
        matches = ".+",
        disabledReason = "Integration test requires POWERTOKENS_API_KEY",
    )
    fun `each smoke model answers a tool-bearing request`(model: String) {
        val client = client(System.getenv("POWERTOKENS_API_KEY"))
        val createParamsBuilder = ChatCompletionCreateParams.builder()
            .model(ChatModel.of(model))
            .maxCompletionTokens(512)
            .addTool(BadgeCode::class.java)
            .addUserMessage(
                "Call the BadgeCode tool for the door named 'north', " +
                    "then reply with the code it returned and nothing else.",
            )

        var toolInvoked = false
        // Ask the model to call BadgeCode, then send the tool result back.
        client.chat().completions().create(createParamsBuilder.build()).choices().stream()
            .map { it.message() }
            .peek { createParamsBuilder.addMessage(it) }
            .flatMap { message -> message.toolCalls().stream().flatMap { Collection.stream(it) } }
            .forEach { toolCall ->
                val function = toolCall.asFunction().function()
                assertEquals("BadgeCode", function.name(), "Unexpected tool: ${function.name()}")
                val result = function.arguments(BadgeCode::class.java).execute()
                toolInvoked = true
                createParamsBuilder.addMessage(
                    ChatCompletionToolMessageParam.builder()
                        .toolCallId(toolCall.asFunction().id())
                        .contentAsJson(result)
                        .build(),
                )
            }

        assertTrue(toolInvoked, "$model never called the BadgeCode tool")

        // Check that the reply includes the code returned by the tool.
        val answer = client.chat().completions().create(createParamsBuilder.build()).choices().stream()
            .flatMap { it.message().content().stream() }
            .findFirst()
            .orElse("")
            .trim()

        assertTrue(
            answer.contains("QP-4471"),
            "The tool result never made it back into the answer from $model, got: $answer",
        )
    }

    /** Check that an invalid API key returns 401 rather than 500. */
    @Test
    fun `powerTokens responds 401 to a key it did not issue`() {
        val client = client(UNKNOWN_KEY)
        val ex = assertThrows<OpenAIServiceException> {
            client.models().list()
        }
        assertEquals(401, ex.statusCode())
    }

    /** Returns a fixed code so the test can check that the model used the tool result. */
    @JsonClassDescription("Returns the current badge code for a named door")
    class BadgeCode {
        @JsonPropertyDescription("The name of the door")
        var doorName: String = ""

        fun execute(): BadgeCodeResult = BadgeCodeResult(code = "QP-4471")
    }

    class BadgeCodeResult(val code: String)
}
