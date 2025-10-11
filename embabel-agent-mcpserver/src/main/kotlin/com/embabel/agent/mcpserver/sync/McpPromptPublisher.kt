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
package com.embabel.agent.mcpserver.sync

import com.embabel.common.core.types.HasInfoString
import io.modelcontextprotocol.server.McpServerFeatures

/**
 * Publishes prompt specifications for the MCP sync server.
 *
 * Implementations provide a list of prompt specifications that can be registered
 * with the server. Extends [HasInfoString] for informational purposes.
 */
interface McpPromptPublisher : HasInfoString {

    /**
     * Returns a list of synchronous prompt specifications to be published.
     *
     * @return a list of [McpServerFeatures.SyncPromptSpecification] objects
     */
    fun prompts(): List<McpServerFeatures.SyncPromptSpecification>

}
