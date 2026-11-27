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
package com.embabel.agent.config.models.deepseek

import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.NativeUsage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.metadata.DefaultUsage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.deepseek.api.DeepSeekApi

class DeepSeekNativeUsageReaderTest {

    @Test
    fun `a cache hit is read as a cache read`() {
        val native = DeepSeekApi.Usage(50, 1000, 1050, DeepSeekApi.Usage.PromptTokensDetails(600))
        val response = ChatResponse(
            listOf(Generation(AssistantMessage("done"))),
            ChatResponseMetadata.builder().usage(DefaultUsage(1000, 50, 1050, native)).build(),
        )
        // The public path DeepSeekModelsConfig wires: a service whose message sender reads usage with this reader.
        val llm = SpringAiLlmService(
            name = "deepseek-chat", provider = "DeepSeek", chatModel = ChatModel { response },
            nativeUsageReader = DeepSeekNativeUsageReader,
        )
        val usage = llm.createMessageSender(LlmOptions()).call(listOf(UserMessage("hi")), emptyList()).usage!!

        assertEquals(NativeUsage(inputTokens = 400, outputTokens = 50, cacheReadInputTokens = 600), usage.billableUsage)
        assertEquals(600, usage.cacheReadInputTokens)
        assertEquals(1000, usage.promptTokens)
    }
}
