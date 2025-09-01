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
package com.embabel.agent.api.common

import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.core.types.ZeroToOne

/**
 * User-facing interface for executing prompts.
 * These are what are executed on a finally configured PromptRunner.
 */
interface PromptRunnerOperations {

    /**
     * Generate text
     */
    infix fun generateText(prompt: String): String =
        createObject(
            prompt = prompt,
            outputClass = String::class.java,
        )

    /**
     * Create an object of the given type using the given prompt and LLM options from context
     * (process context or implementing class).
     * Prompts are typically created within the scope of an
     * @Action method that provides access to
     * domain object instances, offering type safety.
     */
    fun <T> createObject(
        prompt: String,
        outputClass: Class<T>,
    ): T = createObject(
        messages = listOf(UserMessage(prompt)),
        outputClass = outputClass,
    )

    /**
     * Try to create an object of the given type using the given prompt and LLM options from context
     * (process context or implementing class).
     * Prompt is typically created within the scope of an
     * @Action method that provides access to
     * domain object instances, offering type safety.
     */
    fun <T> createObjectIfPossible(
        prompt: String,
        outputClass: Class<T>,
    ): T?

    /**
     * Create an object from messages
     */
    fun <T> createObject(
        messages: List<Message>,
        outputClass: Class<T>,
    ): T

    /**
     * Respond in a conversation
     */
    fun respond(
        messages: List<Message>,
    ): AssistantMessage =
        AssistantMessage(
            createObject(
                messages = messages,
                outputClass = String::class.java,
            )
        )

    /**
     * Use operations from a given template
     */
    fun withTemplate(templateName: String): TemplateOperations

    fun evaluateCondition(
        condition: String,
        context: String,
        confidenceThreshold: ZeroToOne = 0.8,
    ): Boolean

}
