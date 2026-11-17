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
package com.embabel.agent.openai

import com.embabel.agent.api.models.OpenAiModels
import com.embabel.agent.spi.loop.StructuredOutputRequest
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.content.Media
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.util.MimeTypeUtils
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * A GPT-6 model built from a key supplied at runtime does what Embabel Me asks of it: validates,
 * calls a tool at `temperature: 0.0`, answers from the tool's result, reads an image, and answers
 * in the JSON a schema asks for. Each test makes three or four requests per model.
 */
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class Gpt6ByokIT {

    private fun serviceFor(model: String) = OpenAiCompatibleModelFactory.openAi(System.getenv("OPENAI_API_KEY"))
        .validating(model, OpenAiModels.PROVIDER)
        .buildValidated() as SpringAiLlmService

    private fun optionsFor(service: SpringAiLlmService, model: String) =
        service.optionsConverter.convertOptions(LlmOptions.withModel(model).withTemperature(0.0), model) as OpenAiChatOptions

    @ParameterizedTest
    @ValueSource(strings = [OpenAiModels.GPT_6_LUNA, OpenAiModels.GPT_61_SOL, OpenAiModels.GPT_6_ASTRA])
    fun `a GPT-6 BYOK model calls tools and reads images at temperature 0`(model: String) {
        val service = serviceFor(model)

        val withTool = optionsFor(service, model).mutate().toolCallbacks(listOf(TIME_TOOL)).build()
        val toolTurn = service.chatModel.call(Prompt(listOf(UserMessage("What time is it? Use the tool.")), withTool))
        assertEquals("get_time", toolTurn.result.output.toolCalls.single().name, "$model must call the tool")

        val image = UserMessage.builder()
            .text("Is this image red, green or blue? Answer with that one word.")
            .media(listOf(Media.builder().mimeType(MimeTypeUtils.IMAGE_PNG).data(redPng()).build()))
            .build()
        val answer = service.chatModel.call(Prompt(listOf(image), optionsFor(service, model))).result.output.text.orEmpty()
        assertTrue(answer.lowercase().contains("red"), "$model saw: $answer")
    }

    /** Embabel runs the tool loop itself: it replays the call and its result, and the model answers from them. */
    @ParameterizedTest
    @ValueSource(strings = [OpenAiModels.GPT_6_LUNA, OpenAiModels.GPT_61_SOL, OpenAiModels.GPT_6_ASTRA])
    fun `a GPT-6 BYOK model answers from a tool result and in typed JSON`(model: String) {
        val service = serviceFor(model)
        val question = UserMessage("What time is it? Use the tool, then answer with the time.")
        val withTool = optionsFor(service, model).mutate().toolCallbacks(listOf(TIME_TOOL)).build()
        val call = service.chatModel.call(Prompt(listOf(question), withTool)).result.output.toolCalls.single()

        val replay = listOf(
            question,
            AssistantMessage.builder().toolCalls(listOf(call)).build(),
            ToolResponseMessage.builder()
                .responses(listOf(ToolResponseMessage.ToolResponse(call.id, call.name, TIME_TOOL.call(call.arguments))))
                .build(),
        )
        val answer = service.chatModel.call(Prompt(replay, withTool)).result.output.text.orEmpty()
        assertTrue(answer.contains("12"), "$model answered: $answer")

        val typed = service.nativeStructuredOutputConfigurer.configure(
            optionsFor(service, model),
            StructuredOutputRequest(name = "Colour", schema = COLOUR_SCHEMA, strict = false),
            service.nativeSupport,
            null,
        )
        assertNotNull((typed as OpenAiChatOptions).responseFormat, "$model must get native structured output from the catalog")
        val json = service.chatModel.call(Prompt(listOf(UserMessage("Name the colour of a ripe tomato.")), typed))
            .result.output.text.orEmpty()
        assertEquals("red", ObjectMapper().readTree(json)["colour"].asText().lowercase(), "$model answered: $json")
    }

    private companion object {
        const val COLOUR_SCHEMA =
            """{"type":"object","title":"Colour","properties":{"colour":{"type":"string","enum":["red","green","blue"]}},"required":["colour"]}"""

        val TIME_TOOL = object : ToolCallback {
            override fun getToolDefinition(): ToolDefinition = DefaultToolDefinition.builder()
                .name("get_time")
                .description("The current time")
                .inputSchema("""{"type":"object","properties":{}}""")
                .build()

            override fun call(toolInput: String): String = "12:00"
        }

        fun redPng(): ByteArray {
            val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().apply { color = Color.RED; fillRect(0, 0, 64, 64); dispose() }
            return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
        }
    }
}
