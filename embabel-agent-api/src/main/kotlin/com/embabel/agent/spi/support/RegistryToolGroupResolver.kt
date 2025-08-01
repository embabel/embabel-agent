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
package com.embabel.agent.spi.support

import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupMetadata
import com.embabel.agent.core.ToolGroupRequirement
import com.embabel.agent.core.ToolGroupResolution
import com.embabel.agent.spi.ToolGroupResolver
import org.slf4j.LoggerFactory

/**
 * Resolves ToolGroups based on a list.
 * The list is normally Spring-injected,
 * with ToolGroup instances being Spring beans.
 * @param name The name of the resolver.
 * @param toolGroups The list of ToolGroups to resolve. Normally Spring-injected from other beans.
 */
class RegistryToolGroupResolver(
    override val name: String,
    val toolGroups: List<ToolGroup>,
) : ToolGroupResolver {

    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        logger.info(infoString(verbose = true))
    }

    override fun availableToolGroups(): List<ToolGroupMetadata> = toolGroups.map { it.metadata }

    override fun resolveToolGroup(requirement: ToolGroupRequirement): ToolGroupResolution {
        val group = toolGroups.find { it.metadata.role == requirement.role }
        return if (group == null) {
            ToolGroupResolution(
                resolvedToolGroup = null,
                failureMessage = "No tool group matching role '${requirement.role}'",
            )
        } else {
            ToolGroupResolution(
                resolvedToolGroup = group,
            )
        }
    }

    override fun findToolGroupForTool(toolName: String): ToolGroupResolution {
        val group = toolGroups.find { it.toolCallbacks.map { it.toolDefinition.name() }.contains(toolName) }
        return if (group == null) {
            ToolGroupResolution(
                resolvedToolGroup = null,
                failureMessage = "No tool group matching tool '$toolName'",
            )
        } else {
            ToolGroupResolution(
                resolvedToolGroup = group,
            )
        }
    }

    override fun toString(): String {
        return "RegistryToolGroupResolver(name='$name', ${toolGroups.size} toolGroups: ${toolGroups.joinToString(", ") { it.metadata.role }})"
    }

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        if (verbose == false) {
            return "RegistryToolGroupResolver(name='$name', ${toolGroups.size} tool groups)"
        }
        return "RegistryToolGroupResolver: name='$name', ${toolGroups.size} available tool groups:\n\t${
            toolGroups.sortedBy { it.metadata.role }
                .joinToString("\n") {
                    it.infoString(verbose = true, indent = 1)
                }
        }"
    }
}
