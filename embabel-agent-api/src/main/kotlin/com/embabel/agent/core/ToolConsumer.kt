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
import com.embabel.common.core.types.AssetCoordinates
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.Semver
import com.embabel.common.util.indent
import com.embabel.common.util.loggerFor
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import org.springframework.ai.tool.ToolCallback

interface ToolGroupDescription {

    /**
     * Natural language description of the tool group.
     * May be used by an LLM to choose tool groups so should be informative.
     * Tool groups with the same role should have similar descriptions,
     * although they should call out any unique features.
     */
    val description: String

    /**
     * Role of the tool group. Many tool groups can provide this
     * Multiple tool groups can provide the same role,
     * for example with different QoS.
     */
    val role: String

    companion object {

        operator fun invoke(
            description: String,
            role: String,
        ): ToolGroupDescription = ToolGroupDescriptionImpl(
            description = description,
            role = role,
        )

        @JvmStatic
        fun create(
            description: String,
            role: String,
        ): ToolGroupDescription = invoke(
            description = description,
            role = role,
        )
    }

}

private data class ToolGroupDescriptionImpl(
    override val description: String,
    override val role: String,
) : ToolGroupDescription

enum class ToolGroupPermission {
    /**
     * Tool group can be used to modify local resources.
     * This is a strong permission and should be used with caution.
     */
    HOST_ACCESS,

    /**
     * Tool group accesses the internet.
     */
    INTERNET_ACCESS,
}

/**
 * Metadata about a tool group. Interface as platforms
 * may extend it
 */
@JsonDeserialize(`as` = MinimalToolGroupMetadata::class)
interface ToolGroupMetadata : ToolGroupDescription, AssetCoordinates, HasInfoString {

    /**
     * What this tool group's tools are allowed to do.
     */
    val permissions: Set<ToolGroupPermission>

    companion object {
        operator fun invoke(
            description: String,
            role: String,
            name: String,
            provider: String,
            permissions: Set<ToolGroupPermission>,
            version: Semver = Semver(),
        ): ToolGroupMetadata = MinimalToolGroupMetadata(
            description = description,
            role = role,
            name = name,
            provider = provider,
            permissions = permissions,
            version = version,
        )

        operator fun invoke(
            description: ToolGroupDescription,
            name: String,
            provider: String,
            permissions: Set<ToolGroupPermission>,
            version: Semver = Semver(),
        ): ToolGroupMetadata = MinimalToolGroupMetadata(
            description = description.description,
            role = description.role,
            name = name,
            provider = provider,
            permissions = permissions,
            version = version,
        )
    }

}

private data class MinimalToolGroupMetadata(
    override val description: String,
    override val role: String,
    override val name: String,
    override val provider: String,
    override val permissions: Set<ToolGroupPermission>,
    override val version: Semver,
) : ToolGroupMetadata {

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return "role:$role, artifact:$name, version:$version, provider:$provider - $description".indent(indent)
    }
}

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
 * Specifies a tool group that a tool consumer requires.
 */
data class ToolGroupRequirement(
    val role: String,
)

interface ToolGroupConsumer {

    /**
     * Tool groups exposed. This will include directly registered tool groups
     * and tool groups resolved from ToolGroups.
     */
    val toolGroups: Set<ToolGroupRequirement>
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

/**
 * A group of tools to accomplish a purpose, such as web search.
 * Introduces a level of abstraction over tool callbacks.
 * Implements both ToolCallbackPublisher (legacy) and ToolPublisher (preferred).
 */
interface ToolGroup : ToolCallbackPublisher, ToolPublisher, HasInfoString {

    val metadata: ToolGroupMetadata

    /**
     * Default tools implementation returns empty list.
     * Override to provide native Tool instances.
     */
    override val tools: List<Tool>
        get() = emptyList()

    companion object {

        /**
         * Create a ToolGroup from ToolCallbacks (legacy).
         */
        operator fun invoke(
            metadata: ToolGroupMetadata,
            toolCallbacks: List<ToolCallback>,
        ): ToolGroup = ToolGroupImpl(
            metadata = metadata,
            toolCallbacks = toolCallbacks,
            tools = emptyList(),
        )

        /**
         * Create a ToolGroup from native Tools (preferred).
         */
        fun ofTools(
            metadata: ToolGroupMetadata,
            tools: List<Tool>,
        ): ToolGroup = ToolGroupImpl(
            metadata = metadata,
            toolCallbacks = emptyList(),
            tools = tools,
        )
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        val allToolNames = toolCallbacks.map { it.toolDefinition.name() } + tools.map { it.definition.name }
        if (allToolNames.isEmpty()) {
            return metadata.infoString(verbose = true, indent = 1) + "- No tools found".indent(1)
        }
        return when (verbose) {
            true -> metadata.infoString(verbose = true, indent = 1) + " - " +
                    allToolNames.sorted().joinToString().indent(1)

            else -> {
                metadata.infoString(verbose = false)
            }
        }
    }
}

private data class ToolGroupImpl(
    override val metadata: ToolGroupMetadata,
    override val toolCallbacks: List<ToolCallback>,
    override val tools: List<Tool>,
) : ToolGroup

/**
 * Resolution of a tool group request
 * @param failureMessage Failure message in case we could not resolve this group.
 */
data class ToolGroupResolution(
    val resolvedToolGroup: ToolGroup?,
    val failureMessage: String? = null,
)
