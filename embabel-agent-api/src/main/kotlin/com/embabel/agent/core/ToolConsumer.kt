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
package com.embabel.agent.core

import com.embabel.agent.api.common.TerminationScope
import com.embabel.agent.api.tool.TerminateActionException
import com.embabel.agent.api.tool.TerminateAgentException
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.ToolGroupResolver
import com.embabel.agent.spi.loop.RequiredToolGroupException
import com.embabel.common.util.loggerFor

/**
 * Specification for exposing tools using the framework-agnostic Tool interface.
 */
interface ToolSpec {

    /**
     * Tools referenced or exposed.
     */
    val tools: List<Tool>
}

/**
 * Consumer interface for tools using the framework-agnostic Tool interface.
 */
interface ToolSpecConsumer : ToolSpec

/**
 * Publisher interface for tools using the framework-agnostic Tool interface.
 */
interface ToolPublisher : ToolSpec {

    companion object {

        operator fun invoke(tools: List<Tool> = emptyList()) = object : ToolPublisher {
            override val tools: List<Tool> = tools
        }
    }
}

internal interface ToolNameOwner {
    val ownerName: String
}

/**
 * Allows consuming tools and exposing them to LLMs.
 * Interface allowing abstraction between tool concept
 * and specific tools.
 */
interface ToolConsumer : ToolSpecConsumer,
    ToolGroupConsumer {

    val name: String

    /**
     * Tools to expose to LLMs.
     */
    override val tools: List<Tool>
        get() = emptyList()

    /**
     * Resolve all tools from this consumer and its tool groups.
     */
    fun resolveTools(toolGroupResolver: ToolGroupResolver): List<Tool> =
        resolveTools(
            toolConsumer = this,
            toolGroupResolver = toolGroupResolver,
        )

    companion object {
        /**
         * Resolve all tools using the native Tool interface.
         */
        fun resolveTools(
            toolConsumer: ToolConsumer,
            toolGroupResolver: ToolGroupResolver,
        ): List<Tool> = resolveTools(toolConsumer, toolGroupResolver) { it }

        internal fun resolveTools(
            toolConsumer: ToolConsumer,
            toolGroupResolver: ToolGroupResolver,
            name: (Tool) -> Tool,
        ): List<Tool> {
            val resolvedTools = mutableListOf<Tool>()
            resolvedTools += toolConsumer.tools
            for (requirement in toolConsumer.toolGroups) {
                val resolution = toolGroupResolver.resolveToolGroup(requirement)
                if (resolution.resolvedToolGroup == null) {
                    if (requirement.requiredToolNames.isNotEmpty()) {
                        throwForMissingTools(
                            requirement,
                            "Required tool group with role='${requirement.role}' could not be resolved: ${resolution.failureMessage}",
                        )
                    }
                    loggerFor<ToolConsumer>().warn(
                        "Could not resolve tool group with role='{}': {}\n{}",
                        requirement.role,
                        resolution.failureMessage,
                        NO_TOOLS_WARNING,
                    )
                } else if (resolution.resolvedToolGroup.tools.isEmpty()) {
                    if (requirement.requiredToolNames.isNotEmpty()) {
                        throwForMissingTools(
                            requirement,
                            "Required tool group with role='${requirement.role}' has no tools; required: ${requirement.requiredToolNames}",
                        )
                    }
                    loggerFor<ToolConsumer>().warn(
                        "No tools found for tool group with role='{}': {}\n{}",
                        requirement.role,
                        resolution.failureMessage,
                        NO_TOOLS_WARNING,
                    )
                } else {
                    val resolvedToolNames = resolution.resolvedToolGroup.tools.map { it.definition.name }.toSet()
                    val missingToolNames = requirement.requiredToolNames - resolvedToolNames
                    if (missingToolNames.isNotEmpty()) {
                        throwForMissingTools(
                            requirement,
                            "Tool group with role='${requirement.role}' is missing required tools: $missingToolNames. Available: $resolvedToolNames",
                        )
                    }
                    resolvedTools += resolution.resolvedToolGroup.tools
                }
            }
            val publishedTools = resolvedTools.map(name)
                .distinctBy { it.definition.name }
                .sortedBy { it.definition.name }
            loggerFor<ToolConsumer>().debug(
                "{} resolved {} tools from {} tools and {} tool groups: {}",
                toolConsumer.name,
                publishedTools.size,
                toolConsumer.tools.size,
                toolConsumer.toolGroups.size,
                publishedTools.map { it.definition.name },
            )
            return publishedTools
        }
    }
}

private fun throwForMissingTools(
    requirement: ToolGroupRequirement,
    message: String,
): Nothing {
    when (requirement.terminationScope) {
        TerminationScope.AGENT -> throw TerminateAgentException(message)
        TerminationScope.ACTION -> throw TerminateActionException(message)
        null -> throw RequiredToolGroupException(role = requirement.role, message = message)
    }
}

private const val NO_TOOLS_WARNING =
    """

▗▖  ▗▖ ▗▄▖     ▗▄▄▄▖▗▄▖  ▗▄▖ ▗▖    ▗▄▄▖    ▗▄▄▄▖ ▗▄▖ ▗▖ ▗▖▗▖  ▗▖▗▄▄▄
▐▛▚▖▐▌▐▌ ▐▌      █ ▐▌ ▐▌▐▌ ▐▌▐▌   ▐▌       ▐▌   ▐▌ ▐▌▐▌ ▐▌▐▛▚▖▐▌▐▌  █
▐▌ ▝▜▌▐▌ ▐▌      █ ▐▌ ▐▌▐▌ ▐▌▐▌    ▝▀▚▖    ▐▛▀▀▘▐▌ ▐▌▐▌ ▐▌▐▌ ▝▜▌▐▌  █
▐▌  ▐▌▝▚▄▞▘      █ ▝▚▄▞▘▝▚▄▞▘▐▙▄▄▖▗▄▄▞▘    ▐▌   ▝▚▄▞▘▝▚▄▞▘▐▌  ▐▌▐▙▄▄▀



▗▄▄▖ ▗▄▄▖  ▗▄▖ ▗▄▄▖  ▗▄▖ ▗▄▄▖ ▗▖   ▗▄▄▄▖    ▗▖  ▗▖▗▄▄▄▖ ▗▄▄▖ ▗▄▄▖ ▗▄▖ ▗▖  ▗▖▗▄▄▄▖▗▄▄▄▖ ▗▄▄▖▗▖ ▗▖▗▄▄▖  ▗▄▖▗▄▄▄▖▗▄▄▄▖ ▗▄▖ ▗▖  ▗▖
▐▌ ▐▌▐▌ ▐▌▐▌ ▐▌▐▌ ▐▌▐▌ ▐▌▐▌ ▐▌▐▌   ▐▌       ▐▛▚▞▜▌  █  ▐▌   ▐▌   ▐▌ ▐▌▐▛▚▖▐▌▐▌     █  ▐▌   ▐▌ ▐▌▐▌ ▐▌▐▌ ▐▌ █    █  ▐▌ ▐▌▐▛▚▖▐▌
▐▛▀▘ ▐▛▀▚▖▐▌ ▐▌▐▛▀▚▖▐▛▀▜▌▐▛▀▚▖▐▌   ▐▛▀▀▘    ▐▌  ▐▌  █   ▝▀▚▖▐▌   ▐▌ ▐▌▐▌ ▝▜▌▐▛▀▀▘  █  ▐▌▝▜▌▐▌ ▐▌▐▛▀▚▖▐▛▀▜▌ █    █  ▐▌ ▐▌▐▌ ▝▜▌
▐▌   ▐▌ ▐▌▝▚▄▞▘▐▙▄▞▘▐▌ ▐▌▐▙▄▞▘▐▙▄▄▖▐▙▄▄▖    ▐▌  ▐▌▗▄█▄▖▗▄▄▞▘▝▚▄▄▖▝▚▄▞▘▐▌  ▐▌▐▌   ▗▄█▄▖▝▚▄▞▘▝▚▄▞▘▐▌ ▐▌▐▌ ▐▌ █  ▗▄█▄▖▝▚▄▞▘▐▌  ▐▌




"""
