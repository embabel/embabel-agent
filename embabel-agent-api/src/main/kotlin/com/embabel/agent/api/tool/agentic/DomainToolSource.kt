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
package com.embabel.agent.api.tool.agentic

import com.embabel.agent.api.tool.Tool
import org.slf4j.LoggerFactory

/**
 * Configuration for a class that can contribute @LlmTool methods when a single instance is retrieved.
 *
 * When a single artifact of the specified [type] is retrieved during agentic tool execution,
 * any @LlmTool annotated methods on that instance become available as tools.
 *
 * @param T The domain class type
 * @param type The class object
 */
data class DomainToolSource<T : Any>(
    val type: Class<T>,
) {
    companion object {
        /**
         * Create a domain tool source for the given class.
         */
        inline fun <reified T : Any> of(): DomainToolSource<T> = DomainToolSource(T::class.java)
    }
}

/**
 * Tracks domain instances and provides tools from them.
 *
 * When a single instance of a registered domain class is retrieved,
 * this tracker binds @LlmTool methods from that instance.
 */
class DomainToolTracker(
    private val sources: List<DomainToolSource<*>>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Map from domain class to current bound instance (if any)
    private val boundInstances = mutableMapOf<Class<*>, Any>()

    /**
     * Check if the given artifact is a single instance of a registered domain class.
     * If so, bind it and return any tools extracted from it.
     *
     * @return Tools extracted from the instance, or empty list if not applicable
     */
    fun tryBindArtifact(artifact: Any): List<Tool> {
        // Don't bind collections - only single instances
        if (artifact is Iterable<*> || artifact is Array<*>) {
            return emptyList()
        }

        val artifactClass = artifact::class.java

        // Check if this artifact type is registered as a domain tool source
        val source = sources.find { it.type.isAssignableFrom(artifactClass) }
            ?: return emptyList()

        // Check if we already have an instance of this type bound
        if (boundInstances.containsKey(source.type)) {
            logger.debug(
                "Already have a bound instance of {}, not rebinding",
                source.type.simpleName,
            )
            return emptyList()
        }

        // Bind the instance
        boundInstances[source.type] = artifact

        // Extract tools from the instance
        val tools = Tool.safelyFromInstance(artifact)
        if (tools.isNotEmpty()) {
            logger.info(
                "Bound {} instance, exposing {} tools: {}",
                source.type.simpleName,
                tools.size,
                tools.map { it.definition.name },
            )
        }
        return tools
    }

    /**
     * Get the currently bound instance for a domain class, if any.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> getBoundInstance(type: Class<T>): T? = boundInstances[type] as? T

    /**
     * Check if an instance is bound for the given type.
     */
    fun hasBoundInstance(type: Class<*>): Boolean = boundInstances.containsKey(type)
}

/**
 * A tool wrapper that delegates to tools extracted from a domain object.
 * The tool is "declared" to the LLM but returns an error if no instance is bound.
 */
internal class DomainBoundTool(
    private val sourceType: Class<*>,
    private val methodName: String,
    private val methodDescription: String,
    private val inputSchema: Tool.InputSchema,
    private val tracker: DomainToolTracker,
) : Tool {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val definition: Tool.Definition = object : Tool.Definition {
        override val name: String = methodName
        override val description: String = "$methodDescription\n\n" +
            "Note: This tool requires a ${sourceType.simpleName} instance to be retrieved first."
        override val inputSchema: Tool.InputSchema = this@DomainBoundTool.inputSchema
    }

    override val metadata: Tool.Metadata = Tool.Metadata.DEFAULT

    override fun call(input: String): Tool.Result {
        val instance = tracker.getBoundInstance(sourceType)
        if (instance == null) {
            logger.debug(
                "Tool '{}' called but no {} instance is bound",
                methodName,
                sourceType.simpleName,
            )
            return Tool.Result.text(
                "This tool is not yet available. You must first retrieve a single ${sourceType.simpleName} instance."
            )
        }

        // Find and delegate to the actual tool on the instance
        val tools = Tool.safelyFromInstance(instance)
        val delegateTool = tools.find { it.definition.name == methodName }

        if (delegateTool == null) {
            logger.error(
                "Tool '{}' not found on {} instance",
                methodName,
                sourceType.simpleName,
            )
            return Tool.Result.error("Tool '$methodName' not found on ${sourceType.simpleName}")
        }

        logger.info("Executing domain tool '{}' on {} instance", methodName, sourceType.simpleName)
        return delegateTool.call(input)
    }

    override fun toString(): String = "DomainBoundTool($methodName on ${sourceType.simpleName})"
}

/**
 * Creates placeholder tools for a domain class that will be bound when an instance is retrieved.
 * These tools are always declared to the LLM but return errors until an instance is available.
 */
object DomainToolFactory {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Create placeholder tools for all @LlmTool methods on the given class.
     * These tools delegate to the actual instance when one is bound via the tracker.
     *
     * @param source The domain tool source configuration
     * @param tracker The tracker that manages bound instances
     * @return List of placeholder tools, or empty if the class has no @LlmTool methods
     */
    fun createPlaceholderTools(
        source: DomainToolSource<*>,
        tracker: DomainToolTracker,
    ): List<Tool> {
        // Try to get tool definitions by scanning the class
        // We need an instance to extract tools, so create a temporary one if possible
        val tools = try {
            extractToolDefinitions(source.type)
        } catch (e: Exception) {
            logger.warn(
                "Cannot extract tool definitions from {}: {}",
                source.type.simpleName,
                e.message,
            )
            return emptyList()
        }

        return tools.map { toolDef ->
            DomainBoundTool(
                sourceType = source.type,
                methodName = toolDef.name,
                methodDescription = toolDef.description,
                inputSchema = toolDef.inputSchema,
                tracker = tracker,
            )
        }
    }

    /**
     * Extract tool definitions from a class without needing an instance.
     * This scans for @LlmTool annotated methods and extracts their metadata.
     */
    private fun extractToolDefinitions(type: Class<*>): List<Tool.Definition> {
        val annotation = com.embabel.agent.api.annotation.LlmTool::class.java
        return type.kotlin.members
            .filter { member ->
                member.annotations.any { it.annotationClass.java == annotation }
            }
            .mapNotNull { member ->
                val llmToolAnnotation = member.annotations
                    .find { it.annotationClass.java == annotation } as? com.embabel.agent.api.annotation.LlmTool
                    ?: return@mapNotNull null

                object : Tool.Definition {
                    override val name: String = llmToolAnnotation.name.ifEmpty { member.name }
                    override val description: String = llmToolAnnotation.description
                    override val inputSchema: Tool.InputSchema = Tool.InputSchema.empty()
                }
            }
    }
}
