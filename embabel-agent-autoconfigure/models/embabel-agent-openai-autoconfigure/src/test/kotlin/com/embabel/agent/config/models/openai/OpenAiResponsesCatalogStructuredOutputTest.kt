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

import com.embabel.agent.spi.loop.StructuredOutputRequest
import com.openai.client.OpenAIClient
import com.openai.models.responses.Response
import com.openai.models.responses.ResponseCreateParams
import com.openai.models.responses.ToolChoiceOptions
import com.openai.services.blocking.ResponseService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions
import java.util.Optional

/**
 * Runs the real chain — shipped catalog, real configurer, real adapter — so the three stay in
 * step: a `strategy` renamed in the YAML, or a configurer that stopped writing `responseFormat`,
 * would leave a Responses-served model answering in free text with every unit test still green.
 */
class OpenAiResponsesCatalogStructuredOutputTest {

    @Test
    fun `a schema the native support configures reaches the Responses API`() {
        val responsesModel = OpenAiModelLoader().loadAutoConfigMetadata().effectiveModels()
            .first { it.apiFormat == OpenAiApiFormat.RESPONSES }

        val configured = OpenAiNativeStructuredOutputConfigurer.configure(
            options = OpenAiChatOptions.builder().model(responsesModel.modelId).build(),
            structuredOutput = StructuredOutputRequest(
                name = "Answer",
                schema = """{"title":"Answer","type":"object","properties":{"answer":{"type":"string"}}}""",
            ),
            nativeSupport = responsesModel.nativeSupport,
            llm = null,
        )

        val client = mockk<OpenAIClient>()
        val responseService = mockk<ResponseService>()
        val params = slot<ResponseCreateParams>()
        every { client.responses() } returns responseService
        every { responseService.create(capture(params)) } returns emptyResponse(responsesModel.modelId)
        OpenAiResponsesChatModel(client, OpenAiChatOptions.builder().model(responsesModel.modelId).build())
            .call(Prompt(listOf(UserMessage("Hi")), configured))

        val format = params.captured.text().orElseThrow().format().orElseThrow().jsonSchema().orElseThrow()
        assertEquals("Answer", format.name(), "The schema title should name the format")
        assertEquals("object", format.schema()._additionalProperties()["type"]?.asString()?.orElse(null))
    }

    private fun emptyResponse(model: String): Response =
        Response.builder()
            .id("resp_1")
            .createdAt(0.0)
            .model(model)
            .output(emptyList())
            .parallelToolCalls(false)
            .toolChoice(ToolChoiceOptions.AUTO)
            .tools(emptyList())
            .error(Optional.empty())
            .incompleteDetails(Optional.empty())
            .instructions(Optional.empty())
            .metadata(Optional.empty())
            .temperature(Optional.empty())
            .topP(Optional.empty())
            .build()
}
