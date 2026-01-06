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
package com.embabel.agent.api.common.nested

import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import java.util.function.Predicate
import kotlin.reflect.KProperty1

/**
 * An example of creating an object of the given type.
 * Used to provide strongly typed examples to the ObjectCreator.
 * @param T the type of object to create
 * @param description description of the example--e.g. "good example, correct amount of detail"
 * @param value the example object
 */
data class ObjectCreationExample<T>(
    val description: String,
    val value: T,
)

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
    ): ObjectCreator<T> = withExample(
        ObjectCreationExample(
            description = description,
            value = value,
        ),
    )

    /**
     * Convenience method to add multiple examples from a list or other iterable.
     * Each example will be added as a prompt contributor to improve LLM output quality.
     */
    fun withExamples(examples: Iterable<ObjectCreationExample<T>>): ObjectCreator<T> {
        var result: ObjectCreator<T> = this
        examples.forEach {
            result = result.withExample(it)
        }
        return result
    }

    /**
     * Convenience method to add multiple examples using vararg syntax.
     * Each example will be added as a prompt contributor to improve LLM output quality.
     */
    fun withExamples(vararg examples: ObjectCreationExample<T>): ObjectCreator<T> =
        withExamples(examples.asIterable())

    /**
     * Add an example of the desired output to the prompt.
     * This will be included in JSON.
     * It is possible to call this method multiple times.
     * This will override PromptRunner.withGenerateExamples
     */
    fun withExample(
        example: ObjectCreationExample<T>,
    ): ObjectCreator<T>

    /**
     * Adds a filter that determines which properties are to be included when creating an object.
     *
     * Note that each predicate is applied *in addition to* previously registered predicates, including
     * [withProperties] and [withoutProperties].
     * @param filter the property predicate to be added
     */
    fun withPropertyFilter(filter: Predicate<String>): ObjectCreator<T>

    /**
     * Includes the given properties when creating an object.
     *
     * Note that each predicate is applied *in addition to* previously registered predicates, including
     * [withPropertyFilter] and [withoutProperties].
     * @param properties the properties that are to be included
     */
    fun withProperties(vararg properties: String): ObjectCreator<T> = withPropertyFilter { properties.contains(it) }

    /**
     * Excludes the given properties when creating an object.
     *
     * Note that each predicate is applied *in addition to* previously registered predicates, including
     * [withPropertyFilter] and [withProperties].
     * @param properties the properties that are to be included
     */
    fun withoutProperties(vararg properties: String): ObjectCreator<T> = withPropertyFilter { !properties.contains(it) }

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
     * Create an object of this type from the given template
     */
    fun fromTemplate(
        templateName: String,
        model: Map<String, Any>,
    ): T

    /**
     * Create an object of the desired typed from messages
     */
    fun fromMessages(
        messages: List<Message>,
    ): T

}

/**
 * Includes the given properties when creating an object.
 *
 * Note that each predicate is applied *in addition to* previously registered predicates, including
 * [ObjectCreator::withPropertyFilter], [ObjectCreator::withProperties], [ObjectCreator::withoutProperties],
 * and [withoutProperties].
 * @param properties the properties that are to be included
 */
fun <T> ObjectCreator<T>.withProperties(
    vararg properties: KProperty1<T, Any>,
): ObjectCreator<T> =
    withProperties(*properties.map { it.name }.toTypedArray())

/**
 * Excludes the given properties when creating an object.
 *
 * Note that each predicate is applied *in addition to* previously registered predicates, including
 * [ObjectCreator::withPropertyFilter], [ObjectCreator::withProperties], [ObjectCreator::withoutProperties],
 * and [withProperties].
 * @param properties the properties that are to be included
 */
fun <T> ObjectCreator<T>.withoutProperties(
    vararg properties: KProperty1<T, Any>,
): ObjectCreator<T> =
    withoutProperties(*properties.map { it.name }.toTypedArray())
