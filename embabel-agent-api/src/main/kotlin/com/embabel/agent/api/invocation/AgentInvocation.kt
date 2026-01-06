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
package com.embabel.agent.api.invocation

import com.embabel.agent.core.Agent
import com.embabel.agent.core.AgentPlatform
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessOptions
import java.util.concurrent.CompletableFuture


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
interface AgentInvocation<T : Any> : TypedInvocation<T, AgentInvocation<T>> {

    fun withAgentPlatform(agentPlatform: AgentPlatform): AgentInvocation<T>

    @Deprecated("Use returning instead", ReplaceWith("returning(resultType)"))
    fun <U : Any> withResultType(resultType: Class<U>): AgentInvocation<U> = returning(resultType)

    fun <U : Any> returning(resultType: Class<U>): AgentInvocation<U>

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

        @JvmStatic
        fun on(
            agentPlatform: AgentPlatform,
        ): AgentInvocation<Any> = create(agentPlatform, Any::class.java)

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

internal data class DefaultAgentInvocation<T : Any>(
    internal val agentPlatform: AgentPlatform,
    internal val processOptions: ProcessOptions,
    override val resultType: Class<T>,
) : AgentInvocation<T> {

    override fun withAgentPlatform(agentPlatform: AgentPlatform): AgentInvocation<T> =
        copy(agentPlatform = agentPlatform)

    override fun withProcessOptions(options: ProcessOptions): AgentInvocation<T> =
        copy(processOptions = options)

    override fun <U : Any> returning(resultType: Class<U>): AgentInvocation<U> =
        AgentInvocation.builder(this.agentPlatform)
            .options(this.processOptions)
            .build(resultType)

    override fun runAsync(
        obj: Any,
        vararg objs: Any,
    ): CompletableFuture<AgentProcess> {
        val agent = findAgentByResultType() ?: error("No agent with outputClass $resultType found.")
        val args = arrayOf(obj, *objs)

        val agentProcess = agentPlatform.createAgentProcessFrom(
            agent = agent,
            processOptions = processOptions,
            objectsToAdd = args
        )
        return agentPlatform.start(agentProcess)
    }

    override fun runAsync(map: Map<String, Any>): CompletableFuture<AgentProcess> {
        val agent = findAgentByResultType() ?: error("No agent with outputClass $resultType found.")

        val agentProcess = agentPlatform.createAgentProcess(
            agent = agent,
            processOptions,
            bindings = map
        )
        return agentPlatform.start(agentProcess)
    }

    private fun findAgentByResultType(): Agent? =
        agentPlatform.agents().find { agent ->
            agent.goals.any { goal ->
                goal.outputType?.isAssignableTo(resultType) == true
            }
        }

}
