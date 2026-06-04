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
package com.embabel.agent.config.models.ocigenai

import com.oracle.bmc.generativeaiinference.GenerativeAiInference
import com.oracle.bmc.generativeaiinference.model.AssistantMessage
import com.oracle.bmc.generativeaiinference.model.CohereAssistantMessageV2
import com.oracle.bmc.generativeaiinference.model.CohereChatBotMessage
import com.oracle.bmc.generativeaiinference.model.CohereChatRequest
import com.oracle.bmc.generativeaiinference.model.CohereSystemMessage
import com.oracle.bmc.generativeaiinference.model.CohereUserMessage
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.retry.support.RetryTemplate
import org.springframework.ai.chat.messages.AssistantMessage as SpringAssistantMessage

class OciGenAiChatModelTest {

    @Nested
    inner class AssistantMessages {

        @Test
        fun `generic assistant conversion uses empty tool calls when message has no Spring tool calls`() {
            val assistantMessage = chatModel().toGenericMessages(NonSpringAssistantMessage("hello")).single()

            assertTrue(assistantMessage is AssistantMessage)
            val toolCalls = (assistantMessage as AssistantMessage).toolCalls
            assertNotNull(toolCalls)
            assertTrue(toolCalls.isEmpty())
        }

        @Test
        fun `cohere v2 assistant conversion uses empty tool calls when message has no Spring tool calls`() {
            val assistantMessage = chatModel().toCohereV2Messages(NonSpringAssistantMessage("hello")).single()

            assertTrue(assistantMessage is CohereAssistantMessageV2)
            val toolCalls = (assistantMessage as CohereAssistantMessageV2).toolCalls
            assertNotNull(toolCalls)
            assertTrue(toolCalls.isEmpty())
        }
    }

    @Nested
    inner class LegacyCohereRequests {

        @Test
        fun `legacy cohere request history uses last user message position`() {
            val options = options(OciGenAiApiFormat.COHERE)
            val request = chatModel().chatRequest(
                Prompt(
                    listOf(
                        UserMessage("first"),
                        SpringAssistantMessage("same"),
                        UserMessage("same"),
                        SpringAssistantMessage("same"),
                    )
                ),
                options,
            ) as CohereChatRequest

            assertEquals("same", request.message)
            assertEquals(2, request.chatHistory.size)
            assertTrue(request.chatHistory[0] is CohereUserMessage)
            assertEquals("first", (request.chatHistory[0] as CohereUserMessage).message)
            assertTrue(request.chatHistory[1] is CohereChatBotMessage)
            assertEquals("same", (request.chatHistory[1] as CohereChatBotMessage).message)
        }

        @Test
        fun `legacy cohere request keeps system messages only in preamble`() {
            val options = options(OciGenAiApiFormat.COHERE)
            val request = chatModel().chatRequest(
                Prompt(
                    listOf(
                        SystemMessage("You are concise."),
                        UserMessage("first"),
                        SpringAssistantMessage("answer"),
                        UserMessage("second"),
                    )
                ),
                options,
            ) as CohereChatRequest

            assertEquals("You are concise.", request.preambleOverride)
            assertEquals("second", request.message)
            assertEquals(2, request.chatHistory.size)
            assertTrue(request.chatHistory.none { it is CohereSystemMessage })
            assertTrue(request.chatHistory[0] is CohereUserMessage)
            assertEquals("first", (request.chatHistory[0] as CohereUserMessage).message)
            assertTrue(request.chatHistory[1] is CohereChatBotMessage)
            assertEquals("answer", (request.chatHistory[1] as CohereChatBotMessage).message)
        }
    }

    private fun chatModel() = OciGenAiChatModel(
        client = mockk<GenerativeAiInference>(),
        defaultOptions = options(OciGenAiApiFormat.GENERIC),
        retryTemplate = RetryTemplate(),
    )

    private fun options(apiFormat: OciGenAiApiFormat) =
        OciGenAiChatOptions.builder()
            .model("test-model")
            .compartmentId("ocid1.compartment.oc1..test")
            .apiFormat(apiFormat)
            .build()

    private class NonSpringAssistantMessage(
        private val content: String,
    ) : Message {

        override fun getMessageType(): MessageType = MessageType.ASSISTANT

        override fun getText(): String = content

        override fun getMetadata(): Map<String, Any> = emptyMap()
    }
}
