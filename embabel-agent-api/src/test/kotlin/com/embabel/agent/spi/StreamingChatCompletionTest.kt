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
package com.embabel.agent.spi

import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.DefaultOptionsConverter
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.prompt.PromptContributor
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.model.ChatModel
import java.time.LocalDate

class StreamingChatCompletionTest {

    /** An LLM service that can only send messages. It implements neither streaming method. */
    private class TextOnlyLlm : LlmService<TextOnlyLlm> {
        override val name = "text-only"
        override val provider = "test"
        override val knowledgeCutoffDate: LocalDate? = null
        override val pricingModel: PricingModel? = null
        override val promptContributors: List<PromptContributor> = emptyList()
        override fun createMessageSender(options: LlmOptions): LlmMessageSender = mockk()
        override fun withKnowledgeCutoffDate(date: LocalDate) = this
        override fun withPromptContributor(promptContributor: PromptContributor) = this
    }

    @Test
    fun `a Spring AI LLM service can both send and stream chat messages`() {
        val service = SpringAiLlmService("model", "provider", mockk<ChatModel>(), DefaultOptionsConverter)
        assertTrue(service is ChatCompletion)
        assertTrue(service is StreamingChatCompletion)
    }

    @Test
    fun `an LLM service that does not implement streaming reports that it cannot stream`() {
        assertFalse(TextOnlyLlm().supportsStreaming())
    }
}
