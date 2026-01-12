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
package com.embabel.agent.mcpserver

import com.embabel.common.core.types.HasInfoString
import org.springframework.ai.tool.ToolCallback

/**
 * Interface for publishing tools that our MCP server exposes.
 * This is at the MCP export boundary where Spring AI ToolCallback usage is allowed.
 */
interface McpExportToolCallbackPublisher : HasInfoString {

    /**
     * Tool callbacks to expose via MCP.
     */
    val toolCallbacks: List<ToolCallback>
}
