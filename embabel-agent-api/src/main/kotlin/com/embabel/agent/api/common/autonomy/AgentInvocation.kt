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
package com.embabel.agent.api.common.autonomy

import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.ProcessOptions
import java.util.concurrent.CompletableFuture
import java.util.function.Consumer


/**
 * Defines the contract for invoking an agent.
 *
 * Default instances are created with [AgentInvocation.create];
 * [AgentInvocation.builder] allows for customization of the invocation
 * before creation.
 * Once created, [invoke] or [invokeAsync] is used to invoke the agent.
 *
 * @param T type of result returned by the invocation
 */
interface AgentInvocation<T> {

    /**
     * Invokes the agent with one or more arguments.
     *
     * @param obj the first (and possibly only) input value to be added to the blackboard
     * @param objs additional input values to add to the blackboard
     * @return the result of type [T] from the agent invocation
     */
    fun invoke(
        obj: Any,
        vararg objs: Any,
    ): T

    /**
     * Invokes the agent with a map of named inputs.
     *
     * @param map A [Map] that initializes the blackboard
     * @return the result of type [T] from the agent invocation
     */
    fun invoke(map: Map<String, Any>): T

    /**
     * Invokes the agent asynchronously with one or more arguments.
     *
     * @param obj the first (and possibly only) input value to be added to the blackboard
     * @param objs additional input values to add to the blackboard
     * @return the result of type [T] from the agent invocation
     */
    fun invokeAsync(
        obj: Any,
        vararg objs: Any,
    ): CompletableFuture<T>

    /**
     * Invokes the agent asynchronously with a map of named inputs.
     *
     * @param map A [Map] that initializes the blackboard
     * @return the result of type [T] from the agent invocation
     */
    fun invokeAsync(map: Map<String, Any>): CompletableFuture<T>

    companion object {

        /**
         * Create a new [AgentInvocation] for the given platform and explicit result type.
         *
         * @param agentPlatform the platform in which this agent will run
         * @param resultType the Java [Class] of the type of result the agent will return
         * @param T type of result returned by the invocation
         * @return a configured [AgentInvocation] that produces values of type [T]
         */
        @JvmStatic
        fun <T : Any> create(
            agentPlatform: AgentPlatform,
            resultType: Class<T>,
        ): AgentInvocation<T> {
            return builder(agentPlatform).build(resultType)
        }

        /**
         * Create a new [AgentInvocation] for the given platform, inferring the result type
         * from the reified type parameter.
         *
         * @param agentPlatform the platform or environment in which this agent will run
         * @param T type of result returned by the invocation
         * @return a configured [AgentInvocation] that produces values of type [T]
         */
        inline fun <reified T : Any> create(agentPlatform: AgentPlatform): AgentInvocation<T> {
            return builder(agentPlatform).build()
        }

        /**
         * Obtain a new [Builder] to customize agent settings before building.
         *
         * @param agentPlatform the platform or environment in which this agent will run
         * @return a builder through which you can set processing options
         */
        @JvmStatic
        fun builder(agentPlatform: AgentPlatform): Builder {
            return Builder(agentPlatform)
        }

    }

    /**
     * Builder for configuring and creating instances of [AgentInvocation].
     *
     * Use this builder to set process options such as context, blackboard,
     * verbosity, budget, and control policies before constructing the agent invocation.
     */
    class Builder internal constructor(
        private val agentPlatform: AgentPlatform,
    ) {

        private var processOptions = ProcessOptions.DEFAULT

        /**
         * Set the [ProcessOptions] to use for this invocation.
         * @param processOptions the process-level options
         * @return this builder instance for chaining
         */
        fun options(processOptions: ProcessOptions): Builder {
            this.processOptions = processOptions
            return this
        }

        /**
         * Begin configuring process options via a builder.
         * @return a [ProcessOptions.Builder] for fine-grained option setup
         */
        fun options(consumer: Consumer<ProcessOptions.Builder>): Builder {
            val builder = ProcessOptions.builder()
            consumer.accept(builder)
            this.processOptions = builder.build()
            return this
        }

        /**
         * Build the [AgentInvocation] with the given explicit result type.
         * @param resultType the Java [Class] of the result type [T]
         * @return a new [AgentInvocation] producing values of type [T]
         */
        fun <T : Any> build(resultType: Class<T>): AgentInvocation<T> {
            return DefaultAgentInvocation(
                agentPlatform = this.agentPlatform,
                processOptions = this.processOptions,
                resultType = resultType
            )
        }

    }
}


/**
 * Build the [AgentInvocation], inferring the result type from the reified type parameter.
 * @param T type of result returned by the invocation
 * @return a new [AgentInvocation] producing values of type [T]
 */
inline fun <reified T : Any> AgentInvocation.Builder.build(): AgentInvocation<T> {
    return build(T::class.java)
}

internal class DefaultAgentInvocation<T : Any>(
    private val agentPlatform: AgentPlatform,
    private val processOptions: ProcessOptions,
    private val resultType: Class<T>,
) : AgentInvocation<T> {

    override fun invoke(
        obj: Any,
        vararg objs: Any,
    ): T {
        return invokeAsync(obj, *objs)
            .get()
    }

    override fun invoke(map: Map<String, Any>): T {
        return invokeAsync(map)
            .get()
    }

    override fun invokeAsync(
        obj: Any,
        vararg objs: Any,
    ): CompletableFuture<T> {
        val agent = findAgentByResultType() ?: error("No agent with outputClass $resultType found.")
        val args = arrayOf(obj, *objs)

        val agentProcess = agentPlatform.createAgentProcessFrom(
            agent = agent,
            processOptions = processOptions,
            *args
        )
        return agentPlatform.start(agentProcess)
            .thenApply { it.last(resultType) }
    }

    override fun invokeAsync(map: Map<String, Any>): CompletableFuture<T> {
        val agent = findAgentByResultType() ?: error("No agent with outputClass $resultType found.")

        val agentProcess = agentPlatform.createAgentProcess(
            agent = agent,
            processOptions,
            bindings = map
        )
        return agentPlatform.start(agentProcess)
            .thenApply { it.last(resultType) }
    }


    private fun findAgentByResultType(): Agent? =
        agentPlatform.agents().find { agent ->
            agent.goals.any { goal ->
                goal.outputClass?.let(resultType::isAssignableFrom) ?: false
            }
        }

}
