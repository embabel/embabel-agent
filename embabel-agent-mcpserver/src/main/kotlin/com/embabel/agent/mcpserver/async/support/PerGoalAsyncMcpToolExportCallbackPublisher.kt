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
package com.embabel.agent.mcpserver.async.support

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.event.AgentProcessEvent
import com.embabel.agent.event.AgenticEventListener
import com.embabel.agent.event.ObjectAddedEvent
import com.embabel.agent.event.ObjectBoundEvent
import com.embabel.agent.mcpserver.McpToolExportCallbackPublisher
import com.embabel.agent.tools.agent.GoalToolCallback
import com.embabel.agent.tools.agent.PerGoalToolCallbackFactory
import com.embabel.agent.tools.agent.PromptedTextCommunicator
import com.embabel.common.util.indent
import io.modelcontextprotocol.server.McpAsyncServer
import io.modelcontextprotocol.server.McpSyncServerExchange
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Service

/**
 * Implementation of [McpToolExportCallbackPublisher] that delegates to
 * a [PerGoalToolCallbackFactory].
 */
@Service
@ConditionalOnProperty(
    value = ["spring.ai.mcp.server.type"],
    havingValue = "ASYNC",
    matchIfMissing = false,
)
class PerGoalMcpAsyncToolExportCallbackPublisher(
    autonomy: Autonomy,
    private val mcpAsyncServer: McpAsyncServer,
    @Value("\${spring.application.name:agent-api}") applicationName: String,
) : McpToolExportCallbackPublisher {

    private val perGoalToolCallbackFactory = PerGoalToolCallbackFactory(
        autonomy = autonomy,
        applicationName = applicationName,
        textCommunicator = PromptedTextCommunicator,
    )

    override val toolCallbacks: List<ToolCallback>
        get() {
            val goalTools = perGoalToolCallbackFactory.toolCallbacks(
                remoteOnly = true,
                listeners = emptyList(),
            )
            return goalTools.map {
                if (it is GoalToolCallback<*>) {
                    McpAwareToolCallback(it, mcpAsyncServer)
                } else {
                    it
                }
            }
        }


    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "Default MCP Tool Export Callback Publisher: $perGoalToolCallbackFactory".indent(indent)
}


class McpAwareToolCallback(
    val delegate: GoalToolCallback<*>,
    val mcpAsyncServer: McpAsyncServer,
) : ToolCallback {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition


    override fun call(
        toolInput: String,
        toolContext: ToolContext?,
    ): String {
        val exchange = toolContext?.context["exchange"] as? McpSyncServerExchange
        // Make a copy of the delegate with a new listener

        val delegateToCall = if (exchange != null) delegate.withListener(
            McpResourceUpdatingListener(
                mcpAsyncServer,
            )
        ) else delegate
        val result = delegateToCall.call(toolInput, toolContext)
        return result
    }

    override fun call(toolInput: String): String =
        call(toolInput, null).also {
            logger.info("Tool callback called with input: $toolInput, result: $it")
        }

}

class McpResourceUpdatingListener(
    private val mcpAsyncServer: McpAsyncServer,
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
