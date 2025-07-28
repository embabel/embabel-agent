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
package com.embabel.agent.mcpserver

import com.embabel.agent.core.ToolCallbackPublisher
import com.embabel.agent.tools.agent.PerGoalToolCallbackPublisher
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.util.indent
import org.springframework.ai.tool.ToolCallback
import org.springframework.stereotype.Service

/**
 * Tag interface extending Spring AI ToolCallbackProvider
 * that identifies tool callbacks that our MCP server exposes.
 * Will only export tools with Export(remote = true) defined.
 */
interface McpToolExportCallbackPublisher : ToolCallbackPublisher, HasInfoString

/**
 * Implementation of [McpToolExportCallbackPublisher] that delegates to
 * a [PerGoalToolCallbackPublisher].
 */
@Service
class PerGoalMcpToolExportCallbackPublisher(
    private val delegate: PerGoalToolCallbackPublisher,
) : McpToolExportCallbackPublisher {

    override val toolCallbacks: List<ToolCallback> get() = delegate.toolCallbacks(remoteOnly = true)

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String = "Default MCP Tool Export Callback Publisher: $delegate".indent(indent)
}
