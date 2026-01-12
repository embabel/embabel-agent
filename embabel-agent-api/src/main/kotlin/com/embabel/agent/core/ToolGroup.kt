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
import com.embabel.common.core.types.AssetCoordinates
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.Semver
import com.embabel.common.util.indent
import com.fasterxml.jackson.databind.annotation.JsonDeserialize

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
 * A group of tools to accomplish a purpose, such as web search.
 * Introduces a level of abstraction over tools.
 */
interface ToolGroup : ToolPublisher, HasInfoString {

    val metadata: ToolGroupMetadata

    /**
     * Default tools implementation returns empty list.
     * Override to provide native Tool instances.
     */
    override val tools: List<Tool>
        get() = emptyList()

    companion object {

        /**
         * Create a ToolGroup from native Tools.
         */
        operator fun invoke(
            metadata: ToolGroupMetadata,
            tools: List<Tool>,
        ): ToolGroup = ToolGroupImpl(
            metadata = metadata,
            tools = tools,
        )

        /**
         * Create a ToolGroup from native Tools.
         */
        fun ofTools(
            metadata: ToolGroupMetadata,
            tools: List<Tool>,
        ): ToolGroup = ToolGroupImpl(
            metadata = metadata,
            tools = tools,
        )
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        val allToolNames = tools.map { it.definition.name }
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
