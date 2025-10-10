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
package com.embabel.agent.config


import com.embabel.agent.common.Constants.EMBABEL_PROVIDER
import com.embabel.agent.core.CoreToolGroups
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupDescription
import com.embabel.agent.core.ToolGroupPermission
import com.embabel.agent.tools.math.MathTools
import com.embabel.agent.tools.mcp.McpToolGroup
import com.embabel.common.core.types.Semver
import io.modelcontextprotocol.client.McpSyncClient
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.ToolCallback
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

data class GroupConfig(
    val description: String? = null,
    val provider: String = EMBABEL_PROVIDER,
    val tools: Set<String> = emptySet(),
) {

    fun include(tool: ToolCallback): Boolean {
        return tools.any { exclude -> tool.toolDefinition.name().endsWith(exclude) }
    }
}

/**
 * Configuration for ToolGroups when MCP is available
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.tools")
class ToolGroupsProperties {
    /**
     * Map of tool group names to list of tool names to include
     */
    var includes: Map<String, GroupConfig> = emptyMap()

    /**
     * List of tool names to exclude from all tool groups
     */
    var excludes: List<String> = emptyList()

    /**
     * The version of tool groups
     */
    var version: String = Semver().value
}

@Configuration
@EnableConfigurationProperties(
    ToolGroupsProperties::class,
)
class ToolGroupsConfiguration(
    private val mcpSyncClients: List<McpSyncClient>,
    private val properties: ToolGroupsProperties,
) {

    private val logger = LoggerFactory.getLogger(ToolGroupsConfiguration::class.java)

    init {
        logger.info(
            "MCP is available. Found {} clients: {}",
            mcpSyncClients.size,
            mcpSyncClients.map { it.serverInfo }.joinToString("\n"),
        )
    }

    @Bean
    fun includedToolGroups(): List<ToolGroup> {
        val groups = properties.includes.map { (role, gid) ->
            logger.info("Exposing tool group {}", role)
            toToolGroup(role, gid)
        }
        return groups
    }

    @Bean
    fun mathToolGroup() = MathTools()

    private fun toToolGroup(
        role: String,
        gid: GroupConfig,
    ): ToolGroup {
        return McpToolGroup(
            description = ToolGroupDescription(description = gid.description ?: role, role = role),
            name = role,
            provider = gid.provider,
            permissions = setOf(
                ToolGroupPermission.INTERNET_ACCESS
            ),
            clients = mcpSyncClients,
            filter = { tool ->
                val included = gid.tools.any { gid.include(tool) }
                logger.debug(
                    "Tool '{}' included in group {}={} - [{}]", tool.toolDefinition.name(), role, included,
                    gid.tools.joinToString(", ") { t -> "'$t'" }
                )
                included
            }
        )
    }

    @Bean
    fun mcpWebToolsGroup(): ToolGroup {
        val wikipediaTools = setOf(
            "get_related_topics",
            "get_summary",
            "get_article",
            "search_wikipedia",
        )
        return McpToolGroup(
            description = CoreToolGroups.WEB_DESCRIPTION,
            name = "docker-web",
            provider = "Docker",
            permissions = setOf(
                ToolGroupPermission.INTERNET_ACCESS
            ),
            clients = mcpSyncClients,
            filter = {
                // Brave local search is aggressively rate limited, so
                // don't use it for now
                (it.toolDefinition.name().contains("brave") || it.toolDefinition.name().contains("fetch") ||
                        wikipediaTools.any { wt -> it.toolDefinition.name().contains(wt) }) &&
                        !(it.toolDefinition.name().contains("brave_local_search"))
            },
        )
    }

    @Bean
    fun mapsToolsGroup(): ToolGroup {
        return McpToolGroup(
            description = CoreToolGroups.MAPS_DESCRIPTION,
            name = "docker-google-maps",
            provider = "Docker",
            permissions = setOf(
                ToolGroupPermission.INTERNET_ACCESS
            ),
            clients = mcpSyncClients,
            filter = {
                it.toolDefinition.name().contains("maps_")
            }
        )
    }

    @Bean
    fun browserAutomationWebToolsGroup(): ToolGroup {
        return McpToolGroup(
            description = CoreToolGroups.BROWSER_AUTOMATION_DESCRIPTION,
            name = "docker-puppeteer",
            provider = "Docker",
            permissions = setOf(
                ToolGroupPermission.INTERNET_ACCESS
            ),
            clients = mcpSyncClients,
            filter = { it.toolDefinition.name().contains("puppeteer") },
        )
    }

    // TODO this is nasty. Should replace when we have genuine metadata from Docker MCP hub
    private val GitHubTools = listOf(
        "add_issue_comment",
        "create_issue",
        "list_issues",
        "get_issue",
        "list_pull_requests",
        "get_pull_request",
    )

    @Bean
    fun githubToolsGroup(): ToolGroup {
        return McpToolGroup(
            description = CoreToolGroups.GITHUB_DESCRIPTION,
            name = "docker-github",
            provider = "Docker",
            permissions = setOf(
                ToolGroupPermission.INTERNET_ACCESS
            ),
            clients = mcpSyncClients,
            filter = {
                GitHubTools.any { ght ->
                    it.toolDefinition.name().contains(ght)
                }
            },
        )
    }

}
