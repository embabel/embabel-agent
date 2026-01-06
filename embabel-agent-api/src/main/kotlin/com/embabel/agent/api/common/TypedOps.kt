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
package com.embabel.agent.api.common

import com.embabel.agent.core.AgentScope
import com.embabel.agent.core.ProcessOptions
import com.embabel.agent.domain.io.UserInput
import java.util.function.BiFunction


/**
 * Reusable AgentFunction.
 * Allows use of AgentPlatform as a function from I to O,
 * with different process options.
 */
interface AgentFunction<I, O> : BiFunction<I, ProcessOptions, O> {
    val agentScope: AgentScope
    val outputClass: Class<O>
}

/**
 * Enables typed operations using agentic systems
 */
interface TypedOps {

    /**
     * Transform between these two types if possible.
     */
    fun <I : Any, O> transform(
        input: I,
        outputClass: Class<O>,
        processOptions: ProcessOptions = ProcessOptions(),
    ): O = asFunction<I, O>(outputClass).apply(input, processOptions)

    /**
     * Return a reusable function that performs this transformation.
     * Validates whether it's possible and include metadata.
     */
    fun <I : Any, O> asFunction(
        outputClass: Class<O>,
    ): AgentFunction<I, O>

    @Throws(NoSuchAgentException::class)
    fun <I : Any, O> asFunction(
        outputClass: Class<O>,
        agentName: String,
    ): AgentFunction<I, O>

    /**
     * Transform user input into the target type
     */
    fun <O> handleUserInput(
        intent: String,
        outputClass: Class<O>,
        processOptions: ProcessOptions = ProcessOptions(),
    ): O {
        val input = UserInput(intent)
        return transform(input, outputClass, processOptions)
    }

}


/**
 * Perform the magic trick of getting from A to B
 */
inline fun <I : Any, reified O : Any> TypedOps.transform(
    input: I,
    processOptions: ProcessOptions = ProcessOptions(),
): O {
    return transform(
        input = input,
        outputClass = O::class.java,
        processOptions = processOptions,
    )
}

fun <I : Any, O : Any> TypedOps.transform(
    input: I,
    processOptions: ProcessOptions = ProcessOptions(),
    outputClass: Class<O>,
): O {
    return transform(
        input = input,
        outputClass = outputClass,
        processOptions = processOptions,
    )
}

inline fun <I : Any, reified O> TypedOps.asFunction(
): AgentFunction<I, O> = asFunction(O::class.java)
