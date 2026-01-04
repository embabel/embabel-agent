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
package com.embabel.agent.api.common.thinking

import com.embabel.agent.api.common.MultimodalContent
import com.embabel.chat.AssistantMessage
import com.embabel.common.core.thinking.ResponseWithThinking
import com.embabel.common.core.thinking.ThinkingCapability
import com.embabel.chat.Message
import com.embabel.common.core.types.ZeroToOne

/**
 * User-facing interface for executing prompts with thinking block extraction.
 *
 * This interface provides thinking-aware versions of standard prompt operations,
 * returning both the converted results and the reasoning content that LLMs
 * generated during their processing.
 *
 * ## Usage
 *
 * Access this interface through the `withThinking()` extension:
 * ```kotlin
 * val result = promptRunner.withThinking().createObject("analyze this", Person::class.java)
 * val person = result.result        // The converted Person object
 * val thinking = result.thinkingBlocks // List of reasoning blocks
 * ```
 *
 * ## Thinking Block Extraction
 *
 * This interface automatically extracts thinking content in various formats:
 * - Tagged thinking: `<think>reasoning here</think>`, `<analysis>content</analysis>`
 * - Prefix thinking: `//THINKING: reasoning here`
 * - Untagged thinking: raw text content before JSON objects
 *
 * ## Relationship to Regular Operations
 *
 * Unlike [com.embabel.agent.api.common.PromptRunnerOperations] which returns
 * direct objects, all methods in this interface return [ResponseWithThinking]
 * wrappers that provide access to both results and reasoning.
 *
 * @see com.embabel.agent.api.common.PromptRunnerOperations for standard operations
 * @see ResponseWithThinking for the response wrapper
 * @see com.embabel.common.core.thinking.ThinkingBlock for thinking content details
 */
interface ThinkingPromptRunnerOperations : ThinkingCapability {

    /**
     * Generate text with thinking block extraction.
     *
     * @param prompt The text prompt to send to the LLM
     * @return Response containing both generated text and extracted thinking blocks
     */
    infix fun generateText(prompt: String): ResponseWithThinking<String> =
        createObject(
            prompt = prompt,
            outputClass = String::class.java,
        )

    /**
     * Create an object of the given type with thinking block extraction.
     *
     * Uses the given prompt and LLM options from context to generate a structured
     * object while capturing the LLM's reasoning process.
     *
     * @param T The type of object to create
     * @param prompt The text prompt to send to the LLM
     * @param outputClass The class of the object to create
     * @return Response containing both the converted object and extracted thinking blocks
     */
    fun <T> createObject(
        prompt: String,
        outputClass: Class<T>,
    ): ResponseWithThinking<T> = createObject(
        messages = listOf(com.embabel.chat.UserMessage(prompt)),
        outputClass = outputClass,
    )

    /**
     * Try to create an object of the given type with thinking block extraction.
     *
     * Similar to [createObject] but designed for scenarios where the conversion
     * might fail. Returns thinking blocks even when object creation fails.
     *
     * @param T The type of object to create
     * @param prompt The text prompt to send to the LLM
     * @param outputClass The class of the object to create
     * @return Response with potentially null result but always available thinking blocks
     */
    fun <T> createObjectIfPossible(
        prompt: String,
        outputClass: Class<T>,
    ): ResponseWithThinking<T?> = createObjectIfPossible(
        listOf(com.embabel.chat.UserMessage(prompt)),
        outputClass
    )

    /**
     * Try to create an object from messages with thinking block extraction.
     *
     * @param T The type of object to create
     * @param messages The conversation messages to send to the LLM
     * @param outputClass The class of the object to create
     * @return Response with potentially null result but always available thinking blocks
     */
    fun <T> createObjectIfPossible(
        messages: List<Message>,
        outputClass: Class<T>,
    ): ResponseWithThinking<T?>

    /**
     * Create an object from messages with thinking block extraction.
     *
     * @param T The type of object to create
     * @param messages The conversation messages to send to the LLM
     * @param outputClass The class of the object to create
     * @return Response containing both the converted object and extracted thinking blocks
     */
    fun <T> createObject(
        messages: List<Message>,
        outputClass: Class<T>,
    ): ResponseWithThinking<T>

    /**
     * Generate text from multimodal content with thinking block extraction.
     *
     * @param content The multimodal content (text + images) to send to the LLM
     * @return Response containing both generated text and extracted thinking blocks
     */
    fun generateText(content: MultimodalContent): ResponseWithThinking<String> =
        createObject(
            content = content,
            outputClass = String::class.java,
        )

    /**
     * Create an object from multimodal content with thinking block extraction.
     *
     * @param T The type of object to create
     * @param content The multimodal content (text + images) to send to the LLM
     * @param outputClass The class of the object to create
     * @return Response containing both the converted object and extracted thinking blocks
     */
    fun <T> createObject(
        content: MultimodalContent,
        outputClass: Class<T>,
    ): ResponseWithThinking<T> = createObject(
        messages = listOf(com.embabel.chat.UserMessage(content.toContentParts())),
        outputClass = outputClass,
    )

    /**
     * Try to create an object from multimodal content with thinking block extraction.
     *
     * @param T The type of object to create
     * @param content The multimodal content (text + images) to send to the LLM
     * @param outputClass The class of the object to create
     * @return Response with potentially null result but always available thinking blocks
     */
    fun <T> createObjectIfPossible(
        content: MultimodalContent,
        outputClass: Class<T>,
    ): ResponseWithThinking<T?> = createObjectIfPossible(
        listOf(com.embabel.chat.UserMessage(content.toContentParts())),
        outputClass
    )

    /**
     * Respond in a conversation with multimodal content and thinking block extraction.
     *
     * @param content The multimodal content to respond to
     * @return Response containing both the assistant message and extracted thinking blocks
     */
    fun respond(
        content: MultimodalContent,
    ): ResponseWithThinking<AssistantMessage> = respond(
        listOf(com.embabel.chat.UserMessage(content.toContentParts()))
    )

    /**
     * Respond in a conversation with thinking block extraction.
     *
     * @param messages The conversation messages to respond to
     * @return Response containing both the assistant message and extracted thinking blocks
     */
    fun respond(
        messages: List<Message>,
    ): ResponseWithThinking<AssistantMessage>

    /**
     * Evaluate a condition with thinking block extraction.
     *
     * Evaluates a boolean condition using the LLM while capturing its reasoning process.
     *
     * @param condition The condition to evaluate
     * @param context The context for evaluation
     * @param confidenceThreshold The confidence threshold for the evaluation
     * @return Response containing both the evaluation result and extracted thinking blocks
     */
    fun evaluateCondition(
        condition: String,
        context: String,
        confidenceThreshold: ZeroToOne = 0.8,
    ): ResponseWithThinking<Boolean>
}
