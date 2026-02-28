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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolCallContext
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ToolContext
import org.springframework.ai.tool.ToolCallback
import org.springframework.ai.tool.definition.DefaultToolDefinition
import org.springframework.ai.tool.definition.ToolDefinition
import org.springframework.ai.tool.metadata.DefaultToolMetadata
import org.springframework.ai.tool.metadata.ToolMetadata

/**
 * Adapts an Embabel [Tool] to Spring AI's [ToolCallback] interface.
 *
 * This adapter bridges the framework-agnostic Tool abstraction to Spring AI,
 * allowing tools defined using the Embabel API to be used with Spring AI's
 * chat models and tool calling infrastructure.
 */
class SpringToolCallbackAdapter(
    private val tool: Tool,
) : ToolCallback {

    private val logger = LoggerFactory.getLogger(SpringToolCallbackAdapter::class.java)

    override fun getToolDefinition(): ToolDefinition {
        return DefaultToolDefinition.builder()
            .name(tool.definition.name)
            .description(tool.definition.description)
            .inputSchema(tool.definition.inputSchema.toJsonSchema())
            .build()
    }

    override fun getToolMetadata(): ToolMetadata {
        return DefaultToolMetadata.builder()
            .returnDirect(tool.metadata.returnDirect)
            .build()
    }

    override fun call(toolInput: String): String {
        logger.debug("Executing tool '{}' with input: {}", tool.definition.name, toolInput)

        return try {
            when (val result = tool.call(toolInput)) {
                is Tool.Result.Text -> result.content
                is Tool.Result.WithArtifact -> result.content
                is Tool.Result.Error -> {
                    logger.warn("Tool '{}' returned error: {}", tool.definition.name, result.message)
                    "ERROR: ${result.message}"
                }
            }
        } catch (e: Exception) {
            logger.error("Tool '{}' threw exception: {}", tool.definition.name, e.message, e)
            "ERROR: ${e.message ?: "Unknown error"}"
        }
    }

    /**
     * Bridges Spring AI's ToolContext with Embabel's ToolCallContext.
     * Converts any incoming Spring AI ToolContext to an Embabel ToolCallContext
     * and passes it explicitly to the tool.
     */
    override fun call(toolInput: String, toolContext: ToolContext?): String {
        val context = toolContext?.let { ToolCallContext.of(it.context) } ?: ToolCallContext.EMPTY
        return try {
            when (val result = tool.call(toolInput, context)) {
                is Tool.Result.Text -> result.content
                is Tool.Result.WithArtifact -> result.content
                is Tool.Result.Error -> {
                    logger.warn("Tool '{}' returned error: {}", tool.definition.name, result.message)
                    "ERROR: ${result.message}"
                }
            }
        } catch (e: Exception) {
            logger.error("Tool '{}' threw exception: {}", tool.definition.name, e.message, e)
            "ERROR: ${e.message ?: "Unknown error"}"
        }
    }
}

/**
 * Extension function to convert an Embabel Tool to a Spring AI ToolCallback.
 */
fun Tool.toSpringToolCallback(): ToolCallback = SpringToolCallbackAdapter(this)

/**
 * Extension function to convert a list of Embabel Tools to Spring AI ToolCallbacks.
 */
fun List<Tool>.toSpringToolCallbacks(): List<ToolCallback> = map { it.toSpringToolCallback() }

/**
 * Wraps a Spring AI [ToolCallback] as an Embabel [Tool].
 *
 * This reverse adapter allows existing Spring AI tools to be used
 * within the Embabel framework.
 */
class SpringToolCallbackWrapper(
    private val callback: ToolCallback,
) : Tool {

    override val definition: Tool.Definition = object : Tool.Definition {
        override val name: String = callback.toolDefinition.name()
        override val description: String = callback.toolDefinition.description() ?: ""
        override val inputSchema: Tool.InputSchema = SpringInputSchema(callback.toolDefinition)
    }

    override val metadata: Tool.Metadata = object : Tool.Metadata {
        override val returnDirect: Boolean = callback.toolMetadata?.returnDirect() ?: false
        override val providerMetadata: Map<String, Any> = emptyMap()
    }

    override fun call(input: String): Tool.Result {
        return try {
            Tool.Result.text(callback.call(input))
        } catch (e: Exception) {
            Tool.Result.error(e.message ?: "Tool execution failed", e)
        }
    }

    override fun call(input: String, context: ToolCallContext): Tool.Result {
        return try {
            val result = if (context.isEmpty) {
                callback.call(input)
            } else {
                callback.call(input, ToolContext(context.toMap()))
            }
            Tool.Result.text(result)
        } catch (e: Exception) {
            Tool.Result.error(e.message ?: "Tool execution failed", e)
        }
    }
}

/**
 * InputSchema implementation that wraps a Spring AI ToolDefinition.
 */
private class SpringInputSchema(
    private val toolDefinition: ToolDefinition,
) : Tool.InputSchema {

    override fun toJsonSchema(): String = toolDefinition.inputSchema() ?: "{}"

    override val parameters: List<Tool.Parameter>
        get() = emptyList() // Schema is opaque from Spring AI
}

/**
 * Extension function to wrap a Spring AI ToolCallback as an Embabel Tool.
 */
fun ToolCallback.toEmbabelTool(): Tool = SpringToolCallbackWrapper(this)

/**
 * Extension function to wrap a list of Spring AI ToolCallbacks as Embabel Tools.
 */
fun List<ToolCallback>.toEmbabelTools(): List<Tool> = map { it.toEmbabelTool() }
