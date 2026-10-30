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
package com.embabel.agent.spi.support.streaming

import com.embabel.agent.api.common.InteractionId
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.ToolDecorator
import com.embabel.agent.spi.loop.streaming.LlmInferenceStreamEvent
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer
import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import com.embabel.common.core.streaming.StreamingEvent
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import reactor.test.StepVerifier
import tools.jackson.module.kotlin.jacksonObjectMapper

class StreamingLlmOperationsImplTest {

    @Test
    fun `emits provider native thinking through structured stream`() {
        val streamer = object : LlmMessageStreamer {
            override fun stream(
                messages: List<com.embabel.chat.Message>,
                tools: List<com.embabel.agent.api.tool.Tool>,
                toolCallInspectors: List<com.embabel.agent.api.tool.callback.ToolCallInspector>,
            ): Flux<String> = Flux.empty()

            override fun streamInference(
                messages: List<com.embabel.chat.Message>,
                tools: List<com.embabel.agent.api.tool.Tool>,
            ): Flux<LlmInferenceStreamEvent> = Flux.just(
                LlmInferenceStreamEvent.Thinking("native reasoning"),
                LlmInferenceStreamEvent.Content("{\"name\":\"result\"}\n"),
                LlmInferenceStreamEvent.Complete(AssistantMessage("result")),
            )
        }
        val llmService = mockk<LlmService<*>>()
        every { llmService.promptContributors } returns emptyList()
        val operations = StreamingLlmOperationsImpl(
            messageStreamer = streamer,
            objectMapper = jacksonObjectMapper(),
            llmService = llmService,
            toolDecorator = mockk<ToolDecorator>(relaxed = true),
        )
        val interaction = LlmInteraction(
            id = InteractionId("native-thinking-stream"),
            llm = LlmOptions().withThinking(Thinking.withTokenBudget(100)),
        )

        StepVerifier.create(
            operations.createObjectStreamWithThinking(
                messages = listOf(UserMessage("question")),
                interaction = interaction,
                outputClass = ResultItem::class.java,
                agentProcess = mockk<AgentProcess>(relaxed = true),
                action = null,
            )
        )
            .expectNextMatches { event ->
                event is StreamingEvent.Thinking && event.content == "native reasoning"
            }
            .expectNextMatches { event ->
                event is StreamingEvent.Object && event.item.name == "result"
            }
            .verifyComplete()
    }

    data class ResultItem(
        val name: String,
    )
}
