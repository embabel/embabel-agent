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
import com.embabel.agent.api.tool.callback.ToolCallInspector
import com.embabel.agent.spi.loop.streaming.LlmMessageStreamer
import com.embabel.agent.spi.loop.streaming.LlmInferenceStreamEvent
import com.embabel.agent.spi.support.springai.SpringAiLlmMessageSender
import com.embabel.agent.spi.support.springai.toSpringAiMessage
import com.embabel.agent.spi.support.springai.toEmbabelMessage
import com.embabel.agent.spi.support.springai.toEmbabelUsage
import com.embabel.agent.spi.support.springai.toSpringToolCallbacks
import com.embabel.agent.spi.support.springai.ToolResponseContentAdapter
import com.embabel.chat.Message
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.MessageAggregator
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks

/**
 * Spring AI implementation of [LlmMessageStreamer].
 *
 * Streams one inference directly through Spring AI's [ChatModel]. Calling the model
 * directly advertises tools without installing `ToolCallingAdvisor`, leaving execution
 * and continuation to Embabel's streaming tool loop. Consequently, tool-call inspectors
 * are invoked by [com.embabel.agent.spi.loop.streaming.DefaultStreamingToolLoop], around
 * the actual tool execution, rather than by this single-inference adapter.
 *
 * @param chatModel The Spring AI model for streaming
 * @param chatOptions Options for the LLM call (temperature, model, etc.)
 * @see SpringAiLlmMessageSender for non-streaming equivalent
 */
internal class SpringAiLlmMessageStreamer(
    private val chatModel: ChatModel,
    private val chatOptions: ChatOptions,
    private val toolResponseContentAdapter: ToolResponseContentAdapter = ToolResponseContentAdapter.PASSTHROUGH,
) : LlmMessageStreamer {

    override fun stream(
        messages: List<Message>,
        tools: List<Tool>,
        toolCallInspectors: List<ToolCallInspector>,
    ): Flux<String> = streamInference(messages, tools)
        .handle { event, sink ->
            when (event) {
                is LlmInferenceStreamEvent.Content -> sink.next(event.text)
                is LlmInferenceStreamEvent.Thinking -> sink.next(event.text)
                is LlmInferenceStreamEvent.Complete -> Unit
            }
        }

    override fun streamInference(
        messages: List<Message>,
        tools: List<Tool>,
    ): Flux<LlmInferenceStreamEvent> = Flux.defer {
        val springAiMessages = messages
            .map { it.toSpringAiMessage(toolResponseContentAdapter) }
        val toolCallbacks = tools.toSpringToolCallbacks()
        val effectiveOptions = if (chatOptions is org.springframework.ai.model.tool.ToolCallingChatOptions) {
            chatOptions.mutate()
                .toolCallbacks(toolCallbacks)
                .build()
        } else {
            chatOptions
        }
        val prompt = Prompt(springAiMessages, effectiveOptions)
        // Bridges MessageAggregator's terminal callback back into the reactive sequence.
        // It emits one assembled response for this inference turn, after its partial
        // content/tool fragments and before Embabel executes any requested tools.
        val aggregate = Sinks.one<ChatResponse>()

        // Spring providers may split a tool-call ID, name, and JSON arguments across
        // multiple ChatResponse chunks. MessageAggregator leaves those chunks in the
        // returned Flux (so content stays live) and invokes this callback once with the
        // assembled assistant response. The aggregate becomes the terminal SPI event;
        // this adapter deliberately performs no tool execution.
        chatModel.stream(prompt).publish { responses ->
            val content = responses
                .flatMapIterable { response -> listOfNotNull(response.result?.output) }
                .mapNotNull { output -> output.text?.takeIf { it.isNotEmpty() }?.let { output to it } }
                .map<LlmInferenceStreamEvent> { (output, text) ->
                    if (output.metadata["thinking"] == true) {
                        LlmInferenceStreamEvent.Thinking(text)
                    } else {
                        LlmInferenceStreamEvent.Content(text)
                    }
                }
            val completion = MessageAggregator()
                .aggregate(responses, aggregate::tryEmitValue)
                .then(aggregate.asMono())
                .map<LlmInferenceStreamEvent> { response ->
                    val output = response.result?.output
                        ?: throw IllegalStateException("Streaming response contained no assistant output")
                    LlmInferenceStreamEvent.Complete(
                        message = output.toEmbabelMessage(),
                        usage = response.metadata?.usage?.toEmbabelUsage(),
                    )
                }

            Flux.mergeSequential(content, completion)
        }
    }
}
