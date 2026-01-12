package com.embabel.agent.core

import com.embabel.agent.api.tool.Tool
import com.embabel.common.core.types.AssetCoordinates
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.Semver
import com.embabel.common.util.indent
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
