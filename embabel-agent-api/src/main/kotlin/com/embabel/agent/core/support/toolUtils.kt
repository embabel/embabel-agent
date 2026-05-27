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

/**
 * SPI for extracting framework-specific tools (e.g. Spring AI) from an arbitrary
 * object. Implementations are discovered reflectively so that this package has
 * no compile-time dependency on the underlying framework.
 */
internal interface ExternalToolExtractor {
    fun extract(obj: Any): List<Tool>
}

private val externalToolExtractor: ExternalToolExtractor? = try {
    Class.forName("org.springframework.ai.tool.ToolCallback")
    val cls = Class.forName("com.embabel.agent.spi.support.springai.SpringAiToolExtractor")
    cls.getField("INSTANCE").get(null) as ExternalToolExtractor
} catch (_: ClassNotFoundException) {
    null
}

/**
 * Extract native Tools from ToolObject instances.
 */
fun safelyGetTools(instances: Collection<ToolObject>): List<Tool> =
    instances.flatMap { safelyGetToolsFrom(it) }
        .distinctBy { it.definition.name }
        .sortedBy { it.definition.name }

/**
 * Extract native Tools from a single ToolObject.
 * Handles Embabel @LlmTool annotations and direct Tool instances.
 * If a Spring AI extractor is on the classpath, also handles Spring AI
 * @Tool annotations and ToolCallback instances.
 */
fun safelyGetToolsFrom(toolObject: ToolObject): List<Tool> {
    val tools = mutableListOf<Tool>()
    toolObject.objects.forEach { obj ->
        if (obj is Tool) {
            tools.add(obj)
            return@forEach
        }
        tools.addAll(Tool.safelyFromInstance(obj))
        externalToolExtractor?.let { tools.addAll(it.extract(obj)) }
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
 * Allows renaming a Tool while preserving its behavior.
 */
internal class RenamedTool(
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
