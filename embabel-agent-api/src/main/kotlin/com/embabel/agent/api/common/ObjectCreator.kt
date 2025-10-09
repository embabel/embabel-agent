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

import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.prompt.PromptContributor
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Interface to create objects of the given type from a prompt or messages.
 * Allows setting strongly typed examples.
 */
interface ObjectCreator<T> {

    /**
     * Add an example of the desired output to the prompt.
     * This will be included in JSON.
     * It is possible to call this method multiple times.
     * This will override PromptRunner.withGenerateExamples
     */
    fun withExample(
        description: String,
        value: T,
    ): ObjectCreator<T>

    /**
     * Create an object of the desired type using the given prompt and LLM options from context
     * (process context or implementing class).
     * Prompts are typically created within the scope of an
     * @Action method that provides access to
     * domain object instances, offering type safety.
     */
    fun fromPrompt(
        prompt: String,
    ): T = fromMessages(
        messages = listOf(UserMessage(prompt)),
    )

    /**
     * Create an object of the desired typed from messages
     */
    fun fromMessages(
        messages: List<Message>,
    ): T

}

internal data class PromptRunnerObjectCreator<T>(
    internal val promptRunner: PromptRunner,
    internal val outputClass: Class<T>,
    private val objectMapper: ObjectMapper,
) : ObjectCreator<T> {

    override fun withExample(
        description: String,
        value: T,
    ): ObjectCreator<T> {
        return copy(
            promptRunner = promptRunner
                .withGenerateExamples(false)
                .withPromptContributor(
                    PromptContributor.Companion.fixed(
                        """
                        Example: $description
                        ${objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value)}
                        """.trimIndent()
                    )
                )
        )
    }

    override fun fromMessages(
        messages: List<Message>,
    ): T {
        return promptRunner.createObject(
            messages = messages,
            outputClass = outputClass,
        )
    }

}
