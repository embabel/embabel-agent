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
package com.embabel.agent.mcpserver.support

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.mcpserver.McpToolExportCallbackPublisher
import com.embabel.agent.tools.agent.PerGoalToolCallbackPublisher
import com.embabel.agent.tools.agent.PromptedTextCommunicator
import com.embabel.common.util.indent
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.ai.tool.ToolCallback
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * Implementation of [com.embabel.agent.mcpserver.McpToolExportCallbackPublisher] that delegates to
 * a [com.embabel.agent.tools.agent.PerGoalToolCallbackPublisher].
 */
@Service
class PerGoalMcpToolExportCallbackPublisher(
    autonomy: Autonomy,
    objectMapper: ObjectMapper,
    @Value("\${spring.application.name:agent-api}") applicationName: String,
) : McpToolExportCallbackPublisher {

    private val delegate = PerGoalToolCallbackPublisher(
        autonomy = autonomy,
        objectMapper = objectMapper,
        applicationName = applicationName,
        textCommunicator = PromptedTextCommunicator,
    )

    override val toolCallbacks: List<ToolCallback> get() = delegate.toolCallbacks(remoteOnly = true)

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "Default MCP Tool Export Callback Publisher: $delegate".indent(indent)
}
