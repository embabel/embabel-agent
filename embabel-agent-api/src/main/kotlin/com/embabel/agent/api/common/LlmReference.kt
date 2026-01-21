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
package com.embabel.agent.api.common

import com.embabel.agent.api.tool.Tool
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
     */
    fun toolObject(): ToolObject = ToolObject(
        objects = toolInstances(),
        namingStrategy = namingStrategy,
    )

    /**
     * Return the instances of tool object. Defaults to this
     */
    fun toolInstances(): List<Any> = listOf(this)

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
     * Defaults to empty list for backward compatibility.
     *
     * Use this method to provide tools defined using the [Tool] abstraction,
     * which is framework-agnostic and can work with different LLM backends.
     *
     * @see Tool
     * @see toolInstances for Spring AI @Tool annotated objects
     */
    fun tools(): List<Tool> = emptyList()

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
