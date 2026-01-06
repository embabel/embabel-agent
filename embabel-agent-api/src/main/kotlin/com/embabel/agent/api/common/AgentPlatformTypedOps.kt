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

import com.embabel.agent.core.*
import com.embabel.agent.spi.common.Constants
import org.slf4j.LoggerFactory

class NoSuchAgentException(
    val agentName: String,
    val knownAgents: String,
) : IllegalArgumentException("No such agent: '$agentName'. Known agents: $knownAgents")


/**
 * Typed operations over an agent platform
 */
class AgentPlatformTypedOps(
    private val agentPlatform: AgentPlatform,
) : TypedOps {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun <I : Any, O> asFunction(
        outputClass: Class<O>,
    ): AgentFunction<I, O> =
        AgentPlatformBackedAgentFunction(
            outputClass = outputClass,
            agentPlatform = agentPlatform,
        )

    override fun <I : Any, O> asFunction(
        outputClass: Class<O>,
        agentName: String,
    ): AgentFunction<I, O> {
        val agent = agentPlatform.agents().firstOrNull { it.name == agentName }
            ?: throw NoSuchAgentException(agentName, agentPlatform.agents().joinToString { it.name })
        logger.info("Creating function for agent $agentName")
        return AgentBackedAgentFunction(
            outputClass = outputClass,
            agentPlatform = agentPlatform,
            agent = agent,
        )
    }
}

private class AgentPlatformBackedAgentFunction<I : Any, O>(
    override val outputClass: Class<O>,
    private val agentPlatform: AgentPlatform,
) : AgentFunction<I, O> {

    // TODO verify if it's impossible to get from I to O

    override val agentScope: AgentScope
        get() = agentPlatform

    override fun apply(
        input: I,
        processOptions: ProcessOptions,
    ): O {
        val goalAgent = agentPlatform.createAgent(
            name = "goal-${outputClass.simpleName}",
            provider = Constants.EMBABEL_PROVIDER,
            description = "Goal agent for ${outputClass.simpleName}",
        )
            .withSingleGoal(
                Goal(
                    name = "create-${outputClass.simpleName}",
                    description = "Create ${outputClass.simpleName}",
                    satisfiedBy = outputClass,
                )
            )

        val processStatus = agentPlatform.runAgentFrom(
            processOptions = processOptions,
            agent = goalAgent,
            bindings = mapOf(
                IoBinding.DEFAULT_BINDING to input,
            )
        )
        return processStatus.resultOfType(outputClass)
    }
}

private class AgentBackedAgentFunction<I : Any, O>(
    override val outputClass: Class<O>,
    private val agentPlatform: AgentPlatform,
    private val agent: Agent,
) : AgentFunction<I, O> {

    // TODO verify if it's impossible to get from I to O

    override val agentScope: AgentScope
        get() = agentPlatform

    override fun apply(
        input: I,
        processOptions: ProcessOptions,
    ): O {
        val processStatus = agentPlatform.runAgentFrom(
            processOptions = processOptions,
            agent = agent,
            bindings = mapOf(
                IoBinding.DEFAULT_BINDING to input,
            )
        )
        return processStatus.resultOfType(outputClass)
    }
}
