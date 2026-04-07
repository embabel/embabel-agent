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
package com.embabel.agent.spi.support.springai.streaming

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer
import com.embabel.agent.spi.support.springai.SpringAiLlmMessageSender
import com.embabel.agent.spi.support.springai.toSpringAiMessage
import com.embabel.agent.spi.support.springai.toSpringToolCallbacks
import com.embabel.chat.Message
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux

/**
 * Spring AI implementation of [LlmMessageStreamer].
 *
 * Streams raw content chunks from the LLM using Spring AI's ChatClient.
 * Tool execution is handled internally by Spring AI during streaming.
 *
 * @param chatClient The Spring AI ChatClient for streaming
 * @param chatOptions Options for the LLM call (temperature, model, etc.)
 * @see SpringAiLlmMessageSender for non-streaming equivalent
 */
internal class SpringAiLlmMessageStreamer(
    private val chatClient: ChatClient,
    private val chatOptions: ChatOptions,
) : LlmMessageStreamer {

    override fun stream(
        messages: List<Message>,
        tools: List<Tool>,
    ): Flux<String> {
        val springAiMessages = messages.map { it.toSpringAiMessage() }
        val toolCallbacks = tools.toSpringToolCallbacks()
        val prompt = Prompt(springAiMessages)

        return chatClient
            .prompt(prompt)
            .toolCallbacks(toolCallbacks)
            .options(chatOptions)
            .stream()
            .content()
    }
}
