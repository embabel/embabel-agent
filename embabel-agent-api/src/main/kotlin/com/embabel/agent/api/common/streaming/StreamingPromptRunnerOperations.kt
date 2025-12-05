/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
import com.embabel.common.core.streaming.StreamingCapability
import com.embabel.common.core.streaming.StreamingEvent
import reactor.core.publisher.Flux

/**
 * Streaming operations interface providing fluent API for configuring and executing
 * streaming prompts. Follows the same pattern as PromptRunnerOperations but returns
 * reactive streams instead of blocking results.
 *
 * Extends StreamingCapability to enable polymorphic access through the tag interface.
 */
interface StreamingPromptRunnerOperations : StreamingCapability {

    /**
     * Configure the streaming operation with a single prompt message.
     * @param prompt The prompt text to send to the LLM
     * @return StreamingPromptRunnerOperations for method chaining
     */
    fun withPrompt(prompt: String): StreamingPromptRunnerOperations

    /**
     * Configure the streaming operation with a list of messages.
     * @param messages The conversation messages to send to the LLM
     * @return StreamingPromptRunnerOperations for method chaining
     */
    fun withMessages(messages: List<Message>): StreamingPromptRunnerOperations

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
}

/**
 * Extension function to safely cast PromptRunner to streaming operations.
 * Pure casting - assumes streaming support already verified.
 */
fun PromptRunner.asStreaming(): StreamingPromptRunnerOperations {
    return this.stream() as? StreamingPromptRunnerOperations
        ?: throw IllegalStateException("Stream operation did not return StreamingPromptRunnerOperations")
}

/**
 * Extension function to safely convert PromptRunner to streaming operations.
 * Includes validation - checks streaming support before casting.
 */
fun PromptRunner.asStreamingWithValidation(): StreamingPromptRunnerOperations {
    if (!this.supportsStreaming()) {
        throw UnsupportedOperationException("PromptRunner does not support streaming")
    }
    return this.stream() as? StreamingPromptRunnerOperations
        ?: throw IllegalStateException("Stream operation did not return StreamingPromptRunnerOperations")
}
