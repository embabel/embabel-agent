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
package com.embabel.agent.mcpserver.sync.support

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.ObjectAddedEvent
import com.embabel.agent.api.event.ObjectBoundEvent
import com.embabel.agent.mcpserver.McpExportToolCallbackPublisher
import com.embabel.agent.spi.support.springai.toSpringToolCallback
import com.embabel.agent.tools.agent.GoalTool
import com.embabel.agent.tools.agent.PerGoalToolFactory
import com.embabel.agent.tools.agent.PromptedTextCommunicator
import com.embabel.common.util.indent
import io.modelcontextprotocol.server.McpSyncServer
import org.slf4j.LoggerFactory
import org.springframework.ai.tool.ToolCallback
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * Implementation of [McpExportToolCallbackPublisher] that delegates to
 * a [PerGoalToolCallbackFactory].
 */
@Service
@ConditionalOnProperty(
    value = ["spring.ai.mcp.server.type"],
    havingValue = "SYNC",
    matchIfMissing = true,
)
class PerGoalMcpExportToolCallbackPublisher(
    autonomy: Autonomy,
    private val mcpSyncServer: McpSyncServer,
    @Value("\${embabel.agent.application.name:agent-api}") applicationName: String,
) : McpExportToolCallbackPublisher {

    private val perGoalToolFactory = PerGoalToolFactory(
        autonomy = autonomy,
        applicationName = applicationName,
        textCommunicator = PromptedTextCommunicator,
    )

    override val toolCallbacks: List<ToolCallback>
        get() {
            val goalTools = perGoalToolFactory.goalTools(
                remoteOnly = true,
                listeners = emptyList(),
            )
            // Wrap GoalTools with MCP-aware wrapper, then convert to ToolCallback
            val goalCallbacks = goalTools.map { goalTool ->
                McpAwareGoalTool(goalTool, mcpSyncServer).toSpringToolCallback()
            }
            // Include platform tools (e.g. submitFormAndResumeProcess, _confirm) for HITL support
            val platformCallbacks = perGoalToolFactory.platformTools.map { it.toSpringToolCallback() }
            return goalCallbacks + platformCallbacks
        }


    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "Default MCP Tool Export Callback Publisher: $perGoalToolFactory".indent(indent)
}


/**
 * Wraps a GoalTool to add MCP-specific behavior (like resource updates).
 * Implements Tool interface so it can be converted to ToolCallback at the boundary.
 */
class McpAwareGoalTool<I : Any>(
    private val delegate: GoalTool<I>,
    private val mcpSyncServer: McpSyncServer,
) : Tool {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val definition: Tool.Definition = delegate.definition
    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result {
        // Add MCP resource updating listener to the delegate
        val delegateWithListener = delegate.withListener(
            McpResourceUpdatingListener(mcpSyncServer)
        )
        logger.debug("Calling MCP-aware goal tool {} with input: {}", definition.name, input)
        return delegateWithListener.call(input)
    }
}

class McpResourceUpdatingListener(
    private val mcpSyncServer: McpSyncServer,
) : AgenticEventListener {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun onProcessEvent(event: AgentProcessEvent) {
        when {

            event is ObjectBoundEvent -> {
//                val uri = "embabel://agent/${event.value.javaClass.simpleName}/${event.name}"
//                logger.info("MCP Tool Export Callback Publisher adding bound resource {}", uri)
//                mcpSyncServer.addResource(
//                    syncResourceSpecification(
//                        uri = uri,
//                        name = event.name,
//                        description = event.name,
//                        resourceLoader = { exchange ->
//                            event.value.toString()
//                        },
//                    )
//                )
//                mcpSyncServer.notifyResourcesListChanged()
            }

            event is ObjectAddedEvent -> {
//                val uri = "embabel://agent/${event.value.javaClass.simpleName}/it"
//                logger.info("MCP Tool Export Callback Publisher adding resource {}", uri)
//                mcpSyncServer.addResource(
//                    syncResourceSpecification(
//                        uri = uri,
//                        name = event.value.javaClass.simpleName,
//                        description = "Object added",
//                        resourceLoader = { exchange ->
//                            event.value.toString()
//                        },
//                    )
//                )
                // TODO isn't this inefficient? All clients??
//                mcpSyncServer.notifyResourcesListChanged()
            }

            else -> { // Do nothing
            }
        }
    }
}
