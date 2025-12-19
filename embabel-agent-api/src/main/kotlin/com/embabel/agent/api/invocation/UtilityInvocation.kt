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

import com.embabel.agent.api.common.PlannerType
import com.embabel.agent.api.common.scope.AgentScopeBuilder
import com.embabel.agent.core.*
import com.embabel.agent.core.support.NIRVANA
import com.embabel.agent.spi.common.Constants.EMBABEL_PROVIDER
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Invoker for utility agents.
 * Will pick up all @EmbabelComponent and @Agent annotated classes
 * and apply all their actions and goals
 * @param agentPlatform the agent platform to create and manage agent processes
 * @param processOptions options to configure the agent process
 * @param agentScopeBuilder emits the scope to create the utility agent in
 */
data class UtilityInvocation @JvmOverloads constructor(
    private val agentPlatform: AgentPlatform,
    private val processOptions: ProcessOptions = ProcessOptions(),
    private val agentScopeBuilder: AgentScopeBuilder = AgentScopeBuilder.fromPlatform(agentPlatform),
) : BaseInvocation<UtilityInvocation>, ScopedInvocation<UtilityInvocation> {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun withProcessOptions(options: ProcessOptions): UtilityInvocation =
        copy(processOptions = options)

    /**
     * Do we terminate the agent process without error if it gets stuck?
     */
    fun terminateWhenStuck(): UtilityInvocation =
        withProcessOptions(
            processOptions.withAdditionalEarlyTerminationPolicy(EarlyTerminationPolicy.ON_STUCK)
        )

    override fun withScope(agentScopeBuilder: AgentScopeBuilder): UtilityInvocation =
        copy(agentScopeBuilder = agentScopeBuilder)

    override fun runAsync(
        obj: Any,
        vararg objs: Any,
    ): CompletableFuture<AgentProcess> {
        val args = arrayOf(obj, *objs)
        val agentProcess = agentPlatform.createAgentProcessFrom(
            agent = createPlatformAgent(),
            processOptions = validProcessOptions(),
            objectsToAdd = args,
        )
        return agentPlatform.start(agentProcess)
    }

    override fun runAsync(map: Map<String, Any>): CompletableFuture<AgentProcess> {
        val agentProcess = agentPlatform.createAgentProcess(
            agent = createPlatformAgent(),
            processOptions = validProcessOptions(),
            bindings = map,
        )
        return agentPlatform.start(agentProcess)
    }

    /**
     * Create a platform agent for utility invocations
     */
    fun createPlatformAgent(): Agent {
        val agent = agentScopeBuilder
            .build()
            .createAgent(
                name = agentPlatform.name,
                provider = EMBABEL_PROVIDER,
                description = "Platform utility agent",
            )
        // Ensure the agent has the NIRVANA goal to terminate appropriately
        return agent.copy(goals = agent.goals + NIRVANA)
    }

    private fun validProcessOptions(): ProcessOptions {
        return if (processOptions.plannerType == PlannerType.UTILITY) {
            processOptions
        } else {
            logger.info("Correcting plannerType to {} for UtilityInvoker", PlannerType.UTILITY)
            processOptions.copy(
                plannerType = PlannerType.UTILITY
            )
        }
    }

    companion object {

        @JvmStatic
        fun on(
            agentPlatform: AgentPlatform,
        ): UtilityInvocation =
            UtilityInvocation(
                agentPlatform = agentPlatform,
            )
    }
}
