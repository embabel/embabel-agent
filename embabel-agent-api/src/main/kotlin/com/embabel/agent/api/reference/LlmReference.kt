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
package com.embabel.agent.api.reference

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolObject
import com.embabel.agent.api.tool.progressive.UnfoldingTool
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.types.NamedAndDescribed
import com.embabel.common.util.StringTransformer

/**
 * An LLmReference exposes tools and is a prompt contributor.
 * The prompt contribution might describe how to use the tools
 * or can include relevant information directly.
 * Consider, for example, a reference to an API which is so small it's
 * included in the prompt, versus a large API which must be
 * accessed via tools.
 * The reference name is used in a strategy for tool naming, so should be fairly short.
 * Description may be more verbose.
 * If you want a custom naming strategy, use a ToolObject directly,
 * and add the PromptContributor separately.
 */
interface LlmReference : NamedAndDescribed, PromptContributor {

    /**
     * A safe prefix for LLM tools associated with this reference.
     * Defaults to the name lowercased with spaces replaced by underscores.
     * Subclasses can override it
     */
    fun toolPrefix(): String = name.replace(Regex("[^a-zA-Z0-9 ]"), "_").lowercase()

    /**
     * Naming strategy for tools associated with this reference.
     * Defaults to prefixing tool names with the tool prefix and an underscore.
     */
    val namingStrategy: StringTransformer get() = StringTransformer { toolName -> "${toolPrefix()}_$toolName" }

    /**
     * Create a tool object for this reference.
     * @deprecated Use [tools] instead. Tools are now accessed via the tools() method.
     */
    @Deprecated(
        message = "Use tools() instead. Tools are now accessed via the tools() method.",
        level = DeprecationLevel.WARNING,
    )
    fun toolObject(): ToolObject = ToolObject(
        objects = toolInstances(),
        namingStrategy = namingStrategy,
    )

    /**
     * Return the instances of tool object. Defaults to this
     * @deprecated Use [tools] instead. Convert @LlmTool objects using Tool.fromInstance().
     */
    @Deprecated(
        message = "Use tools() instead. Convert @LlmTool objects using Tool.fromInstance().",
        replaceWith = ReplaceWith("tools()"),
        level = DeprecationLevel.WARNING,
    )
    fun toolInstances(): List<Any> = emptyList()

    override fun contribution(): String {
        return """|
            |Reference: $name
            |Description: $description
            |Tool prefix: ${toolPrefix()}
            |Notes: ${notes()}
        """.trimMargin()
    }

    /**
     * Notes about this reference, such as usage guidance.
     * Does not need to consider prompt prefix, name or description as
     * they will be added automatically.
     */
    fun notes(): String

    /**
     * Return framework-agnostic tools provided by this reference.
     * These tools will be added to the PromptRunner when the reference is added.
     *
     * The default implementation bridges from the deprecated [toolInstances] method
     * by converting any @LlmTool annotated objects to [Tool] instances.
     * New implementations should override this method directly.
     *
     * @see Tool
     */
    @Suppress("DEPRECATION")
    fun tools(): List<Tool> {
        val instances = toolInstances()
        if (instances.isEmpty()) {
            return emptyList()
        }
        return instances.flatMap { instance ->
            when (instance) {
                is Tool -> listOf(instance)
                else -> Tool.fromInstance(instance)
            }
        }
    }

    /**
     * Convert this reference to a MatryoshkaReference,
     * exposing a single tool object that supports nesting.
     * Do not rewrap a MatryoshkaReference. Thus
     * repeated calls to this method are safe.
     */
    @Deprecated(
        replaceWith = ReplaceWith(
            expression = "withUnfoldingTool()",
        ),
        message = "Use withUnfoldingTool()",
        level = DeprecationLevel.WARNING,
    )
    fun asMatryoshka(): LlmReference = when (this) {
        is UnfoldingReference -> this
        else -> UnfoldingReference(this)
    }

    /**
     * Convert this reference to a reference exposing a single unfolding tool.
     * Does not rewrap an unfolding reference. Thus
     * repeated calls to this method are safe.
     */
    fun withUnfolding(): LlmReference = when (this) {
        is UnfoldingReference -> this
        else -> UnfoldingReference(this)
    }

    companion object {

        /**
         * Create an LlmReference with tools.
         *
         * @param name The reference name (used as tool prefix)
         * @param description A description of what this reference provides
         * @param notes The text content to include in the prompt
         * @param tools The tools provided by this reference
         * @return An LlmReference with the given content and tools
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            name: String,
            description: String,
            tools: List<Tool>,
            notes: String = "",
        ): LlmReference = SimpleLlmReference(
            name = name,
            description = description,
            notes = notes,
            tools = tools,
        )

        /**
         * Create an LlmReference with a single tool object.
         *
         * @param name The reference name (used as tool prefix)
         * @param description A description of what this reference provides
         * @param notes The text content to include in the prompt
         * @param tool The single tool provided by this reference.
         * May be a [Tool] object or an object with @LlmTool annotated methods.
         * @return An LlmReference with the given content and tools
         */
        @JvmStatic
        @JvmOverloads
        fun of(
            name: String,
            description: String,
            tool: Any,
            notes: String = ""
        ) = fromToolInstances(
            name = name,
            description = description,
            notes = notes,
            toolInstances = arrayOf(tool),
        )

        /**
         * Create an LlmReference from tool instances.
         * Accepts both [Tool] objects directly and objects with @LlmTool annotated methods.
         *
         * @param name The reference name (used as tool prefix)
         * @param description A description of what this reference provides
         * @param notes The text content to include in the prompt
         * @param toolInstances Tool objects or objects containing @LlmTool annotated methods
         * @return An LlmReference with the given tools
         */
        @JvmStatic
        fun fromToolInstances(
            name: String,
            description: String,
            notes: String,
            vararg toolInstances: Any,
        ): LlmReference {
            val tools = toolInstances.flatMap { instance ->
                when (instance) {
                    is Tool -> listOf(instance)
                    else -> Tool.fromInstance(instance)
                }
            }
            return SimpleLlmReference(
                name = name,
                description = description,
                notes = notes,
                tools = tools,
            )
        }
    }
}

/**
 * Simple implementation of LlmReference for factory methods.
 */
private data class SimpleLlmReference(
    override val name: String,
    override val description: String,
    private val notes: String,
    private val tools: List<Tool>,
) : LlmReference {
    override fun notes(): String = notes
    override fun tools(): List<Tool> = tools
}


private class UnfoldingReference(
    private val delegate: LlmReference,
) : LlmReference {

    override val name: String get() = delegate.name

    override val description: String get() = delegate.description

    override fun toolPrefix(): String = delegate.toolPrefix()

    override fun notes(): String = delegate.notes()

    override fun contribution(): String = delegate.contribution()

    @Suppress("DEPRECATION")
    @Deprecated("Use tools() instead", level = DeprecationLevel.WARNING)
    override fun toolInstances(): List<Any> = emptyList()

    override fun tools(): List<Tool> {
        val innerTools = delegate.tools()
        if (innerTools.isEmpty()) {
            return emptyList()
        }
        return listOf(
            UnfoldingTool.of(
                name = delegate.toolPrefix(),
                description = delegate.description,
                innerTools = innerTools,
            )
        )
    }
}
