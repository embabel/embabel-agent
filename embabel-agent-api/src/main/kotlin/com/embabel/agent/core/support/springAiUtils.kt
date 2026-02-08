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
package com.embabel.agent.core.support

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.core.Usage
import com.embabel.agent.spi.support.springai.toEmbabelTool
import com.embabel.agent.spi.support.springai.toSpringToolCallback
import org.springframework.ai.support.ToolCallbacks
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import org.springframework.ai.tool.definition.ToolDefinition

/**
 * Extract native Tools from ToolObject instances.
 * Preferred over safelyGetToolCallbacks as it returns framework-agnostic Tools.
 */
fun safelyGetTools(instances: Collection<ToolObject>): List<Tool> =
    instances.flatMap { safelyGetToolsFrom(it) }
        .distinctBy { it.definition.name }
        .sortedBy { it.definition.name }

/**
 * Extract native Tools from a single ToolObject.
 * Handles Embabel @LlmTool annotations, Spring AI @Tool annotations,
 * and direct Tool/ToolCallback instances.
 */
fun safelyGetToolsFrom(toolObject: ToolObject): List<Tool> {
    val tools = mutableListOf<Tool>()
    toolObject.objects.forEach { obj ->
        // Handle Tool instances directly
        if (obj is Tool) {
            tools.add(obj)
            return@forEach
        }

        // Handle ToolCallback instances by wrapping them
        if (obj is ToolCallback) {
            tools.add(obj.toEmbabelTool())
            return@forEach
        }

        // Scan for Embabel @LlmTool annotations
        val embabelTools = Tool.safelyFromInstance(obj)
        tools.addAll(embabelTools)

        // Scan for Spring AI @Tool annotations and wrap them
        try {
            val springCallbacks = ToolCallbacks.from(obj).toList()
            tools.addAll(springCallbacks.map { it.toEmbabelTool() })
        } catch (_: IllegalStateException) {
            // Ignore - no @Tool annotations found
        }
    }
    return tools
        .filter { toolObject.filter(it.definition.name) }
        .map {
            val newName = toolObject.namingStrategy.transform(it.definition.name)
            if (newName != it.definition.name) {
                RenamedTool(it, newName)
            } else {
                it
            }
        }
        .distinctBy { it.definition.name }
        .sortedBy { it.definition.name }
}

/**
 * Extract tools and convert to Spring AI ToolCallbacks.
 * Internal use only - external code should use [safelyGetTools] and convert at the Spring AI boundary.
 */
internal fun safelyGetToolCallbacks(instances: Collection<ToolObject>): List<ToolCallback> =
    safelyGetTools(instances).map { it.toSpringToolCallback() }

/**
 * Extract tools from a single ToolObject and convert to Spring AI ToolCallbacks.
 * Internal use only - external code should use [safelyGetToolsFrom].
 */
internal fun safelyGetToolCallbacksFrom(toolObject: ToolObject): List<ToolCallback> =
    safelyGetToolsFrom(toolObject).map { it.toSpringToolCallback() }

/**
 * Allows renaming a Tool while preserving its behavior.
 */
class RenamedTool(
    private val delegate: Tool,
    private val newName: String,
) : Tool {

    override val definition: Tool.Definition = object : Tool.Definition {
        override val name: String = newName
        override val description: String = delegate.definition.description
        override val inputSchema: Tool.InputSchema = delegate.definition.inputSchema
    }

    override val metadata: Tool.Metadata = delegate.metadata

    override fun call(input: String): Tool.Result = delegate.call(input)
}

/**
 * Allows renaming a ToolCallback
 */
class RenamedToolCallback(
    private val delegate: ToolCallback,
    private val newName: String,
) : ToolCallback {

    override fun getToolDefinition(): ToolDefinition =
        DefaultToolDefinition(newName, delegate.toolDefinition.description(), delegate.toolDefinition.inputSchema())

    override fun call(toolInput: String): String = delegate.call(toolInput)
}

fun org.springframework.ai.chat.metadata.Usage.toEmbabelUsage(): Usage {
    return Usage(
        promptTokens = this.promptTokens,
        completionTokens = this.completionTokens,
        nativeUsage = this.nativeUsage,
    )
}
