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


import com.embabel.agent.core.CoreToolGroups
import com.embabel.agent.core.ToolGroup
import com.embabel.agent.core.ToolGroupPermission
import com.embabel.agent.tools.math.MathTools
import com.embabel.agent.tools.mcp.McpToolGroup
import io.modelcontextprotocol.client.McpSyncClient
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration


@Configuration(proxyBeanMethods=false)
/*internal*/ class ToolGroupsConfiguration(
    private val mcpSyncClients: List<McpSyncClient>,
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
    fun mathToolGroup() = MathTools()

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
                        !it.toolDefinition.name().contains("brave_local_search")
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
            filter = { GitHubTools.any { ght -> it.toolDefinition.name().contains(ght) } },
        )
    }

}
