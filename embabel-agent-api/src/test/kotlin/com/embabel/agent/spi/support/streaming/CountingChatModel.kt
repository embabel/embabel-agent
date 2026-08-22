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

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.ToolCallingChatOptions
import reactor.core.publisher.Flux
import java.util.concurrent.atomic.AtomicInteger

/**
 * Test [ChatModel] that counts [stream] calls so tests can assert the live capability probe
 * ran once per instance, not on every [com.embabel.agent.spi.LlmService.supportsStreaming] call.
 */
internal class CountingChatModel(
    private val options: ChatOptions = ToolCallingChatOptions.builder().build(),
    private val streamBehavior: () -> Flux<ChatResponse>,
) : ChatModel {
    val streamCalls = AtomicInteger()
    @Volatile
    var lastPrompt: Prompt? = null

    override fun getOptions(): ChatOptions = options

    override fun call(prompt: Prompt): ChatResponse =
        ChatResponse(listOf(Generation(AssistantMessage("unused"))))

    override fun stream(prompt: Prompt): Flux<ChatResponse> {
        lastPrompt = prompt
        streamCalls.incrementAndGet()
        return streamBehavior()
    }
}

internal fun chatResponse(text: String): ChatResponse =
    ChatResponse(listOf(Generation(AssistantMessage(text))))

/**
 * Counts probes like [CountingChatModel], but calls any instance naming the same model equal.
 *
 * The detector must key on identity. An equals-based map, [java.util.WeakHashMap] included, would
 * hand this instance the answer probed from its sibling, which is wrong whenever two instances
 * share a model name and reach different endpoints.
 */
internal class EqualsByNameChatModel(
    private val modelName: String,
    private val streamBehavior: () -> Flux<ChatResponse>,
) : ChatModel {
    val streamCalls = AtomicInteger()

    override fun getOptions(): ChatOptions = ToolCallingChatOptions.builder().build()

    override fun call(prompt: Prompt): ChatResponse =
        ChatResponse(listOf(Generation(AssistantMessage("unused"))))

    override fun stream(prompt: Prompt): Flux<ChatResponse> {
        streamCalls.incrementAndGet()
        return streamBehavior()
    }

    override fun equals(other: Any?): Boolean =
        other is EqualsByNameChatModel && other.modelName == modelName

    override fun hashCode(): Int = modelName.hashCode()
}

/**
 * Relies on Spring AI's default [ChatModel.stream], which throws
 * [UnsupportedOperationException]. Same contract as production fakes that do not override stream.
 */
internal class DefaultStreamChatModel : ChatModel {
    override fun getOptions(): ChatOptions = ToolCallingChatOptions.builder().build()

    override fun call(prompt: Prompt): ChatResponse =
        ChatResponse(listOf(Generation(AssistantMessage("unused"))))
}
