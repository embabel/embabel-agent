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

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.spi.ToolGroupResolver
import com.embabel.agent.spi.support.springai.toSpringToolCallback
import com.embabel.common.util.loggerFor
import org.springframework.ai.tool.ToolCallback

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
     * Resolve all tools from this consumer and its tool groups,
     * converted to Spring AI ToolCallbacks for use with ChatClient.
     */
    fun resolveToolCallbacks(toolGroupResolver: ToolGroupResolver): List<ToolCallback> =
        resolveToolCallbacks(
            toolConsumer = this,
            toolGroupResolver = toolGroupResolver,
        )

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
         * Resolve tools and convert to ToolCallbacks for Spring AI integration.
         */
        fun resolveToolCallbacks(
            toolConsumer: ToolConsumer,
            toolGroupResolver: ToolGroupResolver,
        ): List<ToolCallback> {
            val callbacks = mutableListOf<ToolCallback>()
            // Convert native tools to callbacks
            callbacks += toolConsumer.tools.map { it.toSpringToolCallback() }
            for (role in toolConsumer.toolGroups) {
                val resolution = toolGroupResolver.resolveToolGroup(role)
                if (resolution.resolvedToolGroup == null) {
                    loggerFor<ToolConsumer>().warn(
                        "Could not resolve tool group with role='{}': {}\n{}",
                        role,
                        resolution.failureMessage,
                        NO_TOOLS_WARNING,
                    )
                } else if (resolution.resolvedToolGroup.tools.isEmpty() &&
                    resolution.resolvedToolGroup.toolCallbacks.isEmpty()
                ) {
                    loggerFor<ToolConsumer>().warn(
                        "No tools found for tool group with role='{}': {}\n{}",
                        role,
                        resolution.failureMessage,
                        NO_TOOLS_WARNING,
                    )
                } else {
                    // ToolGroups can still have toolCallbacks for backward compatibility
                    callbacks += resolution.resolvedToolGroup.toolCallbacks
                    callbacks += resolution.resolvedToolGroup.tools.map { it.toSpringToolCallback() }
                }
            }
            loggerFor<ToolConsumer>().debug(
                "{} resolved {} tools from {} tools and {} tool groups: {}",
                toolConsumer.name,
                callbacks.size,
                toolConsumer.tools.size,
                toolConsumer.toolGroups.size,
                callbacks.map { it.toolDefinition.name() },
            )
            return callbacks.distinctBy { it.toolDefinition.name() }.sortedBy { it.toolDefinition.name() }
        }

        /**
         * Resolve all tools using the native Tool interface.
         */
        fun resolveTools(
            toolConsumer: ToolConsumer,
            toolGroupResolver: ToolGroupResolver,
        ): List<Tool> {
            val resolvedTools = mutableListOf<Tool>()
            resolvedTools += toolConsumer.tools
            for (role in toolConsumer.toolGroups) {
                val resolution = toolGroupResolver.resolveToolGroup(role)
                if (resolution.resolvedToolGroup == null) {
                    loggerFor<ToolConsumer>().warn(
                        "Could not resolve tool group with role='{}': {}\n{}",
                        role,
                        resolution.failureMessage,
                        NO_TOOLS_WARNING,
                    )
                } else if (resolution.resolvedToolGroup.tools.isEmpty()) {
                    loggerFor<ToolConsumer>().warn(
                        "No tools found for tool group with role='{}': {}\n{}",
                        role,
                        resolution.failureMessage,
                        NO_TOOLS_WARNING,
                    )
                } else {
                    resolvedTools += resolution.resolvedToolGroup.tools
                }
            }
            loggerFor<ToolConsumer>().debug(
                "{} resolved {} tools from {} tools and {} tool groups: {}",
                toolConsumer.name,
                resolvedTools.size,
                toolConsumer.tools.size,
                toolConsumer.toolGroups.size,
                resolvedTools.map { it.definition.name },
            )
            return resolvedTools.distinctBy { it.definition.name }.sortedBy { it.definition.name }
        }
    }
}

/**
 * Implemented by classes that publish tool callbacks (for backward compatibility with Spring AI tools).
 * New code should implement ToolPublisher instead.
 */
interface ToolCallbackPublisher {

    /**
     * Tool callbacks to expose (legacy, for Spring AI tools).
     */
    val toolCallbacks: List<ToolCallback>

    companion object {

        operator fun invoke(toolCallbacks: List<ToolCallback> = emptyList()) = object : ToolCallbackPublisher {
            override val toolCallbacks: List<ToolCallback> = toolCallbacks
        }
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

