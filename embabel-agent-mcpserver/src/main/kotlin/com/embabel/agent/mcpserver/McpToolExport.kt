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

import com.embabel.agent.api.common.LlmReference
import com.embabel.agent.api.common.ToolObject
import com.embabel.agent.core.support.safelyGetToolCallbacks
import com.embabel.common.util.StringTransformer
import com.embabel.common.util.loggerFor
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * Programmatically export MCP tools from Embabel [ToolObject] or [LlmReference].
 *
 * ## Naming Strategies
 * Tool names can be transformed using a [StringTransformer] naming strategy.
 * This is useful for namespacing tools when exporting from multiple sources:
 * ```kotlin
 * val export = McpToolExport.fromToolObject(
 *     ToolObject(
 *         objects = listOf(myToolInstance),
 *         namingStrategy = { "myprefix_$it" }
 *     )
 * )
 * ```
 *
 * Common naming strategies include:
 * - **Prefix**: `{ "namespace_$it" }` - adds a prefix to avoid conflicts
 * - **Uppercase**: `{ it.uppercase() }` - converts to uppercase
 * - **Identity**: [StringTransformer.IDENTITY] - preserves original names
 *
 * ## Filtering Tools
 * Tools can be filtered using [ToolObject.filter]:
 * ```kotlin
 * val export = McpToolExport.fromToolObject(
 *     ToolObject(
 *         objects = listOf(myToolInstance),
 *         filter = { it.startsWith("public_") }
 *     )
 * )
 * ```
 *
 * ## LlmReference Naming
 * When using [fromLlmReference], the [LlmReference.namingStrategy] is applied automatically,
 * which prefixes tool names with the lowercased reference name. For example, an LlmReference
 * named "MyAPI" will prefix all tools with "myapi_".
 */
interface McpToolExport : McpExportToolCallbackPublisher {

    companion object {

        /**
         * Export the tools on this [ToolObject].
         * The [ToolObject.namingStrategy] and [ToolObject.filter] are applied.
         * @param toolObject the ToolObject containing tool instances
         */
        @JvmStatic
        fun fromToolObject(toolObject: ToolObject): McpToolExport =
            fromToolObjects(listOf(toolObject))

        /**
         * Export tools from multiple [ToolObject] instances.
         * Each ToolObject's [ToolObject.namingStrategy] and [ToolObject.filter] are applied.
         * Tools with duplicate names (after transformation) are deduplicated.
         * @param toolObjects list of ToolObjects to export
         * @param namingStrategy optional additional naming strategy applied after each ToolObject's strategy
         */
        @JvmStatic
        @JvmOverloads
        fun fromToolObjects(
            toolObjects: List<ToolObject>,
            namingStrategy: StringTransformer = StringTransformer.IDENTITY,
        ): McpToolExport {
            val effectiveToolObjects = toolObjects.map { toolObject ->
                toolObject.copy(
                    namingStrategy = StringTransformer(toolObject.namingStrategy, namingStrategy)
                )
            }
            return McpToolExportImpl(
                toolCallbacks = safelyGetToolCallbacks(effectiveToolObjects)
                    .map { decorate(it) },
            )
        }

        /**
         * Export the tools from an [LlmReference].
         * Uses the reference's built-in [LlmReference.namingStrategy], which prefixes
         * tool names with a lowercased, normalized version of the reference name.
         * An additional naming strategy can be applied on top of the reference's strategy.
         *
         * Note that the LlmReference prompt elements won't be exported,
         * so you will need to consider that when prompting downstream
         * LLMs to use the exported tools.
         *
         * @param llmReference the LlmReference to export tools from
         * @param namingStrategy additional naming strategy applied after the reference's strategy
         */
        @JvmStatic
        @JvmOverloads
        fun fromLlmReference(
            llmReference: LlmReference,
            namingStrategy: StringTransformer = StringTransformer.IDENTITY,
        ): McpToolExport {
            return fromToolObjects(listOf(llmReference.toolObject()), namingStrategy)
        }

        /**
         * Export tools from multiple [LlmReference] instances.
         * Each reference's [LlmReference.namingStrategy] is applied, prefixing tool names
         * with the lowercased reference name to avoid naming conflicts.
         * An additional naming strategy can be applied on top of each reference's strategy.
         * @param llmReferences list of LlmReferences to export
         * @param namingStrategy additional naming strategy applied after each reference's strategy
         */
        @JvmStatic
        @JvmOverloads
        fun fromLlmReferences(
            llmReferences: List<LlmReference>,
            namingStrategy: StringTransformer = StringTransformer.IDENTITY,
        ): McpToolExport {
            return fromToolObjects(llmReferences.map { it.toolObject() }, namingStrategy)
        }

        private fun decorate(toolCallback: ToolCallback): ToolCallback {
            return LoggingToolCallback(delegate = toolCallback)
        }

        private class LoggingToolCallback(private var delegate: ToolCallback) : ToolCallback {

            override fun getToolDefinition(): ToolDefinition = delegate.toolDefinition

            override fun call(toolInput: String): String {
                loggerFor<McpToolExport>().info("Calling tool ${delegate.toolDefinition.name()}($toolInput)")
                return delegate.call(toolInput)
            }
        }
    }
}

private class McpToolExportImpl(
    override val toolCallbacks: List<ToolCallback>,
) : McpToolExport {
    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return "McpToolExportCallbackPublisher with ${toolCallbacks.size} tool callbacks"
    }
}
