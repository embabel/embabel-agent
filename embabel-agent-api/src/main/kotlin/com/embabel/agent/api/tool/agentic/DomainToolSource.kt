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
import com.embabel.agent.core.AgentProcess
import org.slf4j.LoggerFactory
import java.util.function.BiPredicate

/**
 * Predicate for filtering domain tool candidates.
 *
 * This functional interface allows you to control whether a domain object
 * should contribute its @LlmTool methods based on the object and current agent process.
 *
 * @param T The domain class type
 */
@FunctionalInterface
fun interface DomainToolPredicate<T> : BiPredicate<T, AgentProcess?> {
    override fun test(artifact: T, agentProcess: AgentProcess?): Boolean

    companion object {
        /**
         * A predicate that always returns true.
         */
        fun <T> always(): DomainToolPredicate<T> = DomainToolPredicate { _, _ -> true }
    }
}

/**
 * Configuration for a class that can contribute @LlmTool methods when a single instance is retrieved.
 *
 * When a single artifact of the specified [type] is retrieved during agentic tool execution,
 * any @LlmTool annotated methods on that instance become available as tools.
 *
 * The optional [predicate] allows filtering which instances should contribute tools.
 * By default, all instances are accepted.
 *
 * @param T The domain class type
 * @param type The class object
 * @param predicate Predicate to filter which instances contribute tools
 */
data class DomainToolSource<T : Any>(
    val type: Class<T>,
    val predicate: DomainToolPredicate<T> = DomainToolPredicate.always(),
) {
    companion object {
        /**
         * Create a domain tool source for the given class.
         */
        inline fun <reified T : Any> of(): DomainToolSource<T> = DomainToolSource(T::class.java)

        /**
         * Create a domain tool source with a predicate.
         */
        inline fun <reified T : Any> of(
            noinline predicate: (T, AgentProcess?) -> Boolean,
        ): DomainToolSource<T> = DomainToolSource(T::class.java, DomainToolPredicate(predicate))
    }
}

/**
 * Tracks domain instances and provides tools from them.
 *
 * When a single instance of a registered domain class is retrieved,
 * this tracker binds @LlmTool methods from that instance.
 *
 * Supports two modes:
 * - **Registered sources mode**: Only binds instances of explicitly registered types
 * - **Auto-discovery mode**: Binds any object with @LlmTool methods, replacing previous bindings
 *
 * In both modes, "last wins" - when a new matching artifact arrives, it replaces any previously bound instance.
 *
 * @param sources List of registered domain tool sources (empty for auto-discovery mode)
 * @param autoDiscovery When true, discovers tools from any object with @LlmTool methods
 * @param agentProcess Optional agent process for predicate evaluation
 */
class DomainToolTracker(
    private val sources: List<DomainToolSource<*>> = emptyList(),
    private val autoDiscovery: Boolean = false,
    private val agentProcess: AgentProcess? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // Map from domain class to current bound instance (if any)
    private val boundInstances = mutableMapOf<Class<*>, Any>()

    /**
     * Check if the given artifact is a single instance of a registered domain class
     * (or any class with @LlmTool methods in auto-discovery mode).
     * If so, bind it and return any tools extracted from it.
     *
     * In "last wins" semantics: if an instance of this type is already bound,
     * it is replaced with the new artifact.
     *
     * @return Tools extracted from the instance, or empty list if not applicable
     */
    @Suppress("UNCHECKED_CAST")
    fun tryBindArtifact(artifact: Any): List<Tool> {
        // Don't bind collections - only single instances
        if (artifact is Iterable<*> || artifact is Array<*>) {
            return emptyList()
        }

        val artifactClass = artifact::class.java

        if (autoDiscovery) {
            return tryBindAutoDiscovered(artifact, artifactClass)
        }

        // Check if this artifact type is registered as a domain tool source
        val source = sources.find { it.type.isAssignableFrom(artifactClass) }
            ?: return emptyList()

        // Check predicate
        val typedPredicate = source.predicate as DomainToolPredicate<Any>
        if (!typedPredicate.test(artifact, agentProcess)) {
            logger.debug(
                "Predicate rejected {} instance, not binding",
                source.type.simpleName,
            )
            return emptyList()
        }

        // Bind the instance (replaces any previous binding - "last wins")
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
     * Auto-discovery mode: bind any object with @LlmTool methods.
     * Clears all previous bindings when a new object is bound.
     */
    private fun tryBindAutoDiscovered(artifact: Any, artifactClass: Class<*>): List<Tool> {
        // Check if this object has any @LlmTool methods
        val tools = Tool.safelyFromInstance(artifact)
        if (tools.isEmpty()) {
            return emptyList()
        }

        // Clear all previous bindings (auto-discovery keeps only the last)
        boundInstances.clear()

        // Bind this instance
        boundInstances[artifactClass] = artifact

        logger.info(
            "Auto-discovered {} instance, exposing {} tools: {}",
            artifactClass.simpleName,
            tools.size,
            tools.map { it.definition.name },
        )
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

    companion object {
        /**
         * Create a tracker in auto-discovery mode.
         * This will expose tools from any object with @LlmTool methods,
         * replacing previous bindings when new objects are discovered.
         */
        fun withAutoDiscovery(agentProcess: AgentProcess? = null): DomainToolTracker =
            DomainToolTracker(
                sources = emptyList(),
                autoDiscovery = true,
                agentProcess = agentProcess,
            )
    }
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
