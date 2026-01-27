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
package com.embabel.agent.api.common.support

import com.embabel.agent.api.common.streaming.StreamingPromptRunner
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.core.streaming.StreamingEvent
import reactor.core.publisher.Flux

/**
 * Implementation of [StreamingPromptRunner.Streaming] that delegates to a [PromptExecutionDelegate].
 */
internal data class DelegatingStreaming(
    private val delegate: PromptExecutionDelegate,
) : StreamingPromptRunner.Streaming {

    override fun withPrompt(prompt: String): StreamingPromptRunner.Streaming =
        copy(delegate = delegate.withMessages(listOf(UserMessage(prompt))))

    override fun withMessages(messages: List<Message>): StreamingPromptRunner.Streaming =
        copy(delegate = delegate.withMessages((messages)))

    override fun generateStream(): Flux<String> {
        return delegate.generateStream()
    }

    override fun <T> createObjectStream(itemClass: Class<T>): Flux<T> =
        delegate.createObjectStream(itemClass)

    override fun <T> createObjectStreamWithThinking(itemClass: Class<T>): Flux<StreamingEvent<T>> =
        delegate.createObjectStreamWithThinking(itemClass)
}
