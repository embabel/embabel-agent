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

import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.ProcessOptions
import java.util.concurrent.CompletableFuture

/**
 * Defines the contract for invoking an agent without a specific return type.
 *
 * Default instances are created with [AgentInvocation.create];
 * [AgentInvocation.builder] allows for customization of the invocation
 * before creation.
 * Once created, [run] or [runAsync] is used to run the agent.
 */
interface BaseInvocation<THIS : BaseInvocation<THIS>> {

    fun withProcessOptions(options: ProcessOptions): THIS

    /**
     * Runs the agent with one or more arguments
     *
     * @param obj the first (and possibly only) input value to be added to the blackboard
     * @param objs additional input values to add to the blackboard
     * @return the agent process
     */
    fun run(
        obj: Any,
        vararg objs: Any,
    ): AgentProcess = runAsync(obj, *objs).get()

    /**
     * Runs the agent with a map of named inputs.
     *
     * @param map A [Map] that initializes the blackboard
     * @return the agent process
     */
    fun run(map: Map<String, Any>): AgentProcess = runAsync(map).get()

    /**
     * Runs the agent asynchronously with one or more arguments
     *
     * @param obj the first (and possibly only) input value to be added to the blackboard
     * @param objs additional input values to add to the blackboard
     * @return the future agent process
     */
    fun runAsync(
        obj: Any,
        vararg objs: Any,
    ): CompletableFuture<AgentProcess>

    /**
     * Runs the agent asynchronously with a map of named inputs.
     *
     * @param map A [Map] that initializes the blackboard
     * @return the future agent process
     */
    fun runAsync(map: Map<String, Any>): CompletableFuture<AgentProcess>

}
