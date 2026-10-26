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
package com.embabel.agent.config.models.googlegenai

import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.agent.spi.support.springai.toSpringAiMessage
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.ToolCall
import com.embabel.chat.ToolResultMessage
import com.embabel.chat.UserMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.google.genai.GoogleGenAiChatModel
import java.lang.reflect.InvocationTargetException

/**
 * What Gemini is sent for a tool result, built the way a tool loop builds it: the auto-configured
 * service's tool response adapter, the message conversion, then Spring AI's own request builder,
 * which parses every tool result as JSON. Nothing goes over the network: the request is built and
 * read, not sent.
 *
 * A tool result of plain text that starts with a bracket used to reach the builder unwrapped and
 * fail it, on every retry (#2196).
 */
class GoogleGenAiToolResponseRequestTest {

    /** The function response Gemini would receive for a tool that answered [toolResult]. */
    private fun functionResponseFor(toolResult: String): Map<String, Any> {
        var response: Map<String, Any>? = null
        googleGenAiRunner().run { context ->
            val service = context.getBeansOfType(SpringAiLlmService::class.java).values.first()
            val chatModel = service.chatModel as GoogleGenAiChatModel
            val messages = listOf(
                UserMessage("Write the files"),
                AssistantMessageWithToolCalls(toolCalls = listOf(ToolCall("call-1", "writeFiles", "{}"))),
                ToolResultMessage("call-1", "writeFiles", toolResult),
            ).map { it.toSpringAiMessage(service.toolResponseContentAdapter) }

            val request = buildRequest(chatModel, Prompt(messages))

            response = request.contents()
                .flatMap { it.parts().orElse(emptyList()) }
                .mapNotNull { it.functionResponse().orElse(null) }
                .single()
                .response()
                .get()
        }
        return response!!
    }

    /**
     * The two steps [GoogleGenAiChatModel.call] takes before sending: the prompt merged with the model's
     * options, then the request built from it, which is the step that parses tool results. Both are
     * private to Spring AI.
     */
    private fun buildRequest(chatModel: GoogleGenAiChatModel, prompt: Prompt): GoogleGenAiChatModel.GeminiRequest {
        val withOptions = invoke(chatModel, "buildRequestPrompt", prompt) as Prompt
        return invoke(chatModel, "createGeminiRequest", withOptions) as GoogleGenAiChatModel.GeminiRequest
    }

    private fun invoke(chatModel: GoogleGenAiChatModel, method: String, prompt: Prompt): Any =
        try {
            GoogleGenAiChatModel::class.java
                .getDeclaredMethod(method, Prompt::class.java)
                .apply { isAccessible = true }
                .invoke(chatModel, prompt)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }

    @Test
    fun `plain text that starts with a bracket reaches Gemini wrapped`() {
        val text = "[Submission accepted] The total has been written to output/total.json."

        assertEquals(mapOf("result" to text), functionResponseFor(text))
    }

    @Test
    fun `malformed JSON-like text reaches Gemini wrapped`() {
        assertEquals(mapOf("result" to "{not json}"), functionResponseFor("{not json}"))
    }

    @Test
    fun `valid JSON followed by text reaches Gemini wrapped`() {
        assertEquals(mapOf("result" to "[1] done"), functionResponseFor("[1] done"))
    }

    @Test
    fun `a bare JSON value reaches Gemini wrapped`() {
        assertEquals(mapOf("result" to "42"), functionResponseFor("42"))
    }

    @Test
    fun `plain text reaches Gemini wrapped`() {
        assertEquals(mapOf("result" to "3 files written"), functionResponseFor("3 files written"))
    }

    @Test
    fun `a JSON object reaches Gemini as that object`() {
        assertEquals(mapOf("count" to 5, "status" to "ok"), functionResponseFor("""{"count": 5, "status": "ok"}"""))
    }

    @Test
    fun `a JSON array reaches Gemini as its result`() {
        assertEquals(mapOf("result" to listOf(1, 2, 3)), functionResponseFor("[1, 2, 3]"))
    }
}
