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
package com.embabel.agent.rag

import org.springframework.ai.tool.ToolCallback

/**
 * Tool retrieved by a RAG request
 */
data class ContextualTool(
    val toolCallback: ToolCallback,
) : Retrievable {

    override fun embeddableValue(): String =
        toolCallback.toolDefinition.description()


    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return "tool: " + toolCallback.toolDefinition.name()
    }

    override val id: String
        get() = "tool:${toolCallback.toolDefinition.name()}"

    override val uri: String?
        get() = null

    override val metadata: Map<String, Any?>
        // TODO fix this
        get() = emptyMap() //toolCallback.toolMetadata
}
