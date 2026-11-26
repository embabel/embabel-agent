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
package com.embabel.agent.spi.support

import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.event.ModelCallEvent
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.loop.LlmMessageRequest
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.NativeStructuredOutputRequest
import com.embabel.agent.spi.loop.RequestAwareLlmMessageSender
import com.embabel.chat.Message

internal class StructuredOutputLlmMessageSender(
    private val delegate: LlmMessageSender,
    private val nativeStructuredOutputRequest: NativeStructuredOutputRequest,
) : LlmMessageSender {

    override fun call(
        messages: List<Message>,
        tools: List<Tool>,
    ): LlmMessageResponse {
        return if (delegate is RequestAwareLlmMessageSender) {
            delegate.call(
                LlmMessageRequest(
                    messages = messages,
                    tools = tools,
                    nativeStructuredOutputRequest = nativeStructuredOutputRequest,
                )
            )
        } else {
            delegate.call(messages, tools)
        }
    }
}

/**
 * Emits a [ModelCallEvent] for each call to the model, then delegates.
 *
 * Wraps the sender from the [com.embabel.agent.spi.LlmService] directly, so the event carries
 * exactly what that sender is given. Request-aware: a [RequestAwareLlmMessageSender] delegate
 * still gets the full [LlmMessageRequest], native structured output included.
 */
internal class ModelCallEventLlmMessageSender(
    private val delegate: LlmMessageSender,
    private val llmRequestEvent: LlmRequestEvent<*>,
) : RequestAwareLlmMessageSender {

    override fun call(
        messages: List<Message>,
        tools: List<Tool>,
    ): LlmMessageResponse {
        emit(messages, tools)
        return delegate.call(messages, tools)
    }

    override fun call(request: LlmMessageRequest): LlmMessageResponse {
        emit(request.messages, request.tools)
        return if (delegate is RequestAwareLlmMessageSender) {
            delegate.call(request)
        } else {
            delegate.call(request.messages, request.tools)
        }
    }

    private fun emit(messages: List<Message>, tools: List<Tool>) {
        llmRequestEvent.agentProcess.processContext.onProcessEvent(
            // Snapshot: the tool loop keeps appending to the list it passes on each round.
            ModelCallEvent(request = llmRequestEvent, messages = messages.toList(), tools = tools.toList())
        )
    }
}
