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
package com.embabel.agent.api.common.streaming

import com.embabel.agent.api.common.PromptRunner
import com.embabel.chat.Message
import com.embabel.common.core.streaming.StreamingEvent
import reactor.core.publisher.Flux

/**
 * Extension of PromptRunner that provides streaming capabilities for progressive
 * LLM response processing. Enables real-time object creation and thinking streams.
 *
 * Implementations of this interface guarantee streaming support and override
 * the base PromptRunner behavior to provide real streaming operations.
 *
 * Usage:
 * ```kotlin
 * val streamingRunner = context.ai().withAutoLlm() as StreamingPromptRunner
 * val restaurantStream = streamingRunner.stream()
 *     .withPrompt("Find 5 restaurants in Paris")
 *     .createObjectStream(Restaurant::class.java)
 * ```
 */
interface StreamingPromptRunner : PromptRunner {

    /**
     * StreamingPromptRunner implementations always support streaming.
     * Overrides the base PromptRunner default of false.
     */
    override fun supportsStreaming(): Boolean = true

    /**
     * Return a [StreamingPromptRunner.Streaming] for reactive streaming operations.
     *
     * @return streaming operations for reactive object and text generation
     */
    override fun streaming(): Streaming

    /**
     * Fluent interface for reactive streaming operations from LLM responses.
     * Provides configuration options for:
     *
     * - Streaming object creation
     * - Streaming with thinking content
     *
     * Instances are obtained via [StreamingPromptRunner.streaming].
     */
    interface Streaming : PromptRunner.StreamingCapability {
        /**
         * Configure the streaming operation with a single prompt message.
         * @param prompt The prompt text to send to the LLM
         * @return StreamingPromptRunner.Streaming for method chaining
         */
        fun withPrompt(prompt: String): Streaming

        /**
         * Configure the streaming operation with a list of messages.
         * @param messages The conversation messages to send to the LLM
         * @return StreamingPromptRunner.Streaming for method chaining
         */
        fun withMessages(messages: List<Message>): Streaming

        /**
         * Create a reactive stream of objects of the specified type.
         * Objects are emitted as they become available during LLM processing.
         *
         * @param itemClass The class of objects to create
         * @return Flux emitting objects as they are parsed from the LLM response
         */
        fun <T> createObjectStream(itemClass: Class<T>): Flux<T>

        /**
         * Create a reactive stream with both objects and thinking content.
         * Provides access to the LLM's reasoning process alongside the results.
         *
         * @param itemClass The class of objects to create
         * @return Flux emitting StreamingEvent instances for objects and thinking
         */
        fun <T> createObjectStreamWithThinking(itemClass: Class<T>): Flux<StreamingEvent<T>>

        /**
         * Generate a reactive stream of text chunks as they arrive from the LLM.
         *
         * @return Flux emitting text chunks as they arrive from the LLM
         */
        fun generateStream(): Flux<String>

    }
}

/**
 * Extension function to safely cast PromptRunner to streaming operations.
 * Pure casting - assumes streaming support already verified.
 */
fun PromptRunner.asStreaming(): StreamingPromptRunner.Streaming {
    return this.streaming() as? StreamingPromptRunner.Streaming
        ?: throw IllegalStateException("Stream operation did not return StreamingPromptRunner.Streaming")
}

/**
 * Extension function to safely convert PromptRunner to streaming operations.
 * Includes validation - checks streaming support before casting.
 */
fun PromptRunner.asStreamingWithValidation(): StreamingPromptRunner.Streaming {
    if (!this.supportsStreaming()) {
        throw UnsupportedOperationException("PromptRunner does not support streaming")
    }
    return this.streaming() as? StreamingPromptRunner.Streaming
        ?: throw IllegalStateException("Stream operation did not return StreamingPromptRunner.Streaming")
}
