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
package com.embabel.agent.api.invocation

import java.util.concurrent.CompletableFuture

/**
 * Defines the contract for invoking an agent with a specific return type.
 *
 * Extends [UntypedInvocation] to add typed [invoke] and [invokeAsync] methods
 * that extract and return a result of type [T] from the completed agent process.
 *
 * @param T type of result returned by the invocation
 */
interface TypedInvocation<T : Any> : UntypedInvocation {

    /**
     * The class of the result type this invocation will produce.
     */
    val resultType: Class<T>

    /**
     * Invokes the agent with one or more arguments and returns the typed result.
     *
     * @param obj the first (and possibly only) input value to be added to the blackboard
     * @param objs additional input values to add to the blackboard
     * @return the result of type [T] from the agent invocation
     */
    fun invoke(
        obj: Any,
        vararg objs: Any,
    ): T = invokeAsync(obj, *objs).get()

    /**
     * Invokes the agent with a map of named inputs and returns the typed result.
     *
     * @param map A [Map] that initializes the blackboard
     * @return the result of type [T] from the agent invocation
     */
    fun invoke(map: Map<String, Any>): T = invokeAsync(map).get()

    /**
     * Invokes the agent asynchronously with one or more arguments.
     *
     * @param obj the first (and possibly only) input value to be added to the blackboard
     * @param objs additional input values to add to the blackboard
     * @return a future containing the result of type [T]
     */
    fun invokeAsync(
        obj: Any,
        vararg objs: Any,
    ): CompletableFuture<T> = runAsync(obj, *objs).thenApply { it.last(resultType) }

    /**
     * Invokes the agent asynchronously with a map of named inputs.
     *
     * @param map A [Map] that initializes the blackboard
     * @return a future containing the result of type [T]
     */
    fun invokeAsync(map: Map<String, Any>): CompletableFuture<T> =
        runAsync(map).thenApply { it.last(resultType) }
}
