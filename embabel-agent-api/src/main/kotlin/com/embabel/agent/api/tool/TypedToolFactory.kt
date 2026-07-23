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
package com.embabel.agent.api.tool

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.function.Function

/**
 * Factory interface for creating strongly typed tools.
 * Extended by [Tool.Companion] to provide [Tool.fromFunction] methods.
 *
 * Example (Java):
 * ```java
 * record AddRequest(int a, int b) {}
 * record AddResult(int sum) {}
 *
 * Tool tool = Tool.fromFunction(
 *     "add",
 *     "Add two numbers",
 *     AddRequest.class,
 *     AddResult.class,
 *     input -> new AddResult(input.a() + input.b())
 * );
 * ```
 *
 * Example (Kotlin):
 * ```kotlin
 * data class AddRequest(val a: Int, val b: Int)
 * data class AddResult(val sum: Int)
 *
 * val tool = Tool.fromFunction<AddRequest, AddResult>(
 *     name = "add",
 *     description = "Add two numbers",
 * ) { input -> AddResult(input.a + input.b) }
 * ```
 */
interface TypedToolFactory {

    /**
     * Create a tool with strongly typed input and output (Java-friendly).
     *
     * @param I Input type - will be deserialized from JSON
     * @param O Output type - will be serialized to JSON
     * @param name Tool name
     * @param description Tool description
     * @param inputType Class of the input type
     * @param outputType Class of the output type
     * @param function Function that processes typed input and returns typed output
     * @return A new Tool instance
     */
    fun <I : Any, O : Any> fromFunction(
        name: String,
        description: String,
        inputType: Class<I>,
        outputType: Class<O>,
        function: Function<I, O>,
    ): Tool = fromFunction(name, description, inputType, outputType, Tool.Metadata.DEFAULT, function)

    /**
     * Create a tool with strongly typed input and output with custom metadata (Java-friendly).
     *
     * @param I Input type - will be deserialized from JSON
     * @param O Output type - will be serialized to JSON
     * @param name Tool name
     * @param description Tool description
     * @param inputType Class of the input type
     * @param outputType Class of the output type
     * @param metadata Tool metadata
     * @param function Function that processes typed input and returns typed output
     * @return A new Tool instance
     */
    fun <I : Any, O : Any> fromFunction(
        name: String,
        description: String,
        inputType: Class<I>,
        outputType: Class<O>,
        metadata: Tool.Metadata,
        function: Function<I, O>,
    ): Tool = fromFunction(name, description, inputType, outputType, metadata, jacksonObjectMapper(), function)

    /**
     * Create a tool with strongly typed input and output with custom ObjectMapper (Java-friendly).
     *
     * @param I Input type - will be deserialized from JSON
     * @param O Output type - will be serialized to JSON
     * @param name Tool name
     * @param description Tool description
     * @param inputType Class of the input type
     * @param outputType Class of the output type
     * @param metadata Tool metadata
     * @param objectMapper ObjectMapper for JSON serialization/deserialization
     * @param function Function that processes typed input and returns typed output
     * @return A new Tool instance
     */
    fun <I : Any, O : Any> fromFunction(
        name: String,
        description: String,
        inputType: Class<I>,
        outputType: Class<O>,
        metadata: Tool.Metadata,
        objectMapper: ObjectMapper,
        function: Function<I, O>,
    ): Tool = TypedTool(
        name = name,
        description = description,
        inputType = inputType,
        outputType = outputType,
        metadata = metadata,
        objectMapper = objectMapper,
        function = function,
    )
}

/**
 * Kotlin-friendly extension for creating typed tools with reified type parameters.
 */
inline fun <reified I : Any, reified O : Any> TypedToolFactory.fromFunction(
    name: String,
    description: String,
    metadata: Tool.Metadata = Tool.Metadata.DEFAULT,
    objectMapper: ObjectMapper = jacksonObjectMapper(),
    noinline function: (I) -> O,
): Tool = fromFunction(
    name = name,
    description = description,
    inputType = I::class.java,
    outputType = O::class.java,
    metadata = metadata,
    objectMapper = objectMapper,
    function = Function { input -> function(input) },
)
