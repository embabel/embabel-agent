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
package com.embabel.agent.tools.blackboard

import com.embabel.agent.api.tool.MatryoshkaTool
import com.embabel.agent.api.tool.Tool
import com.embabel.agent.core.AgentProcess
import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.satisfiesType
import com.embabel.chat.agent.BlackboardEntryFormatter
import com.embabel.chat.agent.DefaultBlackboardEntryFormatter
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

/**
 * Tools for accessing objects in the current process blackboard.
 *
 * This is a [MatryoshkaTool] that exposes sub-tools for:
 * - Listing all objects
 * - Getting objects by binding name
 * - Getting the last object of a type
 * - Describing/formatting objects
 * - Counting objects of a type
 *
 * Uses [AgentProcess.get] to access the current process's blackboard.
 *
 * Example usage:
 * ```kotlin
 * val tools = BlackboardTools().create()
 * // Add to an agentic tool
 * SimpleAgenticTool("assistant", "...")
 *     .withTools(tools)
 * ```
 */
class BlackboardTools {

    private val objectMapper = jacksonObjectMapper()

    /**
     * Create a MatryoshkaTool for blackboard access.
     *
     * @param entryFormatter Formatter for individual blackboard entries
     */
    @JvmOverloads
    fun create(
        entryFormatter: BlackboardEntryFormatter = DefaultBlackboardEntryFormatter,
    ): MatryoshkaTool = MatryoshkaTool.of(
        name = "blackboard",
        description = "Access objects in the current process context. " +
            "Invoke to see available operations for listing, getting, and describing objects.",
        innerTools = listOf(
            createListTool(),
            createGetTool(entryFormatter),
            createLastTool(entryFormatter),
            createDescribeTool(entryFormatter),
            createCountTool(),
        ),
        childToolUsageNotes = "Use blackboard_list to see what's available. " +
            "Use blackboard_get for named bindings, blackboard_last for the most recent of a type.",
    )

    private fun getBlackboardOrError(): Pair<Blackboard?, Tool.Result?> {
        val agentProcess = AgentProcess.get()
            ?: return null to Tool.Result.text("No agent process available. Blackboard tools require an active agent context.")
        return agentProcess.blackboard to null
    }

    private fun createListTool(): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = "blackboard_list",
            description = "List all objects currently in the blackboard with their types and indices",
            inputSchema = Tool.InputSchema.empty(),
        )

        override fun call(input: String): Tool.Result {
            val (blackboard, error) = getBlackboardOrError()
            if (error != null) return error

            val objects = blackboard!!.objects
            if (objects.isEmpty()) {
                return Tool.Result.text("Blackboard is empty - no objects available.")
            }

            val listing = objects.mapIndexed { index, obj ->
                val typeName = obj::class.simpleName ?: obj::class.java.name
                "[$index] $typeName"
            }.joinToString("\n")

            return Tool.Result.text("Blackboard contains ${objects.size} object(s):\n$listing")
        }
    }

    private fun createGetTool(entryFormatter: BlackboardEntryFormatter): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = "blackboard_get",
            description = "Get an object by its binding name",
            inputSchema = Tool.InputSchema.of(
                Tool.Parameter.string("name", "The binding name to look up", required = true)
            ),
        )

        override fun call(input: String): Tool.Result {
            val (blackboard, error) = getBlackboardOrError()
            if (error != null) return error

            val params = parseParams(input)
            val name = params["name"] as? String
                ?: return Tool.Result.text("Missing required parameter: name")

            val value = blackboard!![name]
            if (value == null) {
                return Tool.Result.text("No object found with binding name '$name'")
            }

            val formatted = entryFormatter.format(value)
            val typeName = value::class.simpleName ?: value::class.java.name
            return Tool.Result.text("$typeName bound to '$name':\n$formatted")
        }
    }

    private fun createLastTool(entryFormatter: BlackboardEntryFormatter): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = "blackboard_last",
            description = "Get the most recent object of a given type. Matches by simple class name or fully qualified name.",
            inputSchema = Tool.InputSchema.of(
                Tool.Parameter.string("typeName", "The type name (e.g., 'User' or 'com.example.User')", required = true)
            ),
        )

        override fun call(input: String): Tool.Result {
            val (blackboard, error) = getBlackboardOrError()
            if (error != null) return error

            val params = parseParams(input)
            val typeName = params["typeName"] as? String
                ?: return Tool.Result.text("Missing required parameter: typeName")

            val last = blackboard!!.objects.lastOrNull { satisfiesType(it, typeName) }
            if (last == null) {
                return Tool.Result.text("No object of type '$typeName' found in blackboard")
            }

            val formatted = entryFormatter.format(last)
            val actualType = last::class.simpleName ?: last::class.java.name
            return Tool.Result.text("Last $actualType:\n$formatted")
        }
    }

    private fun createDescribeTool(entryFormatter: BlackboardEntryFormatter): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = "blackboard_describe",
            description = "Get a detailed description/formatting of an object by its binding name",
            inputSchema = Tool.InputSchema.of(
                Tool.Parameter.string("name", "The binding name of the object to describe", required = true)
            ),
        )

        override fun call(input: String): Tool.Result {
            val (blackboard, error) = getBlackboardOrError()
            if (error != null) return error

            val params = parseParams(input)
            val name = params["name"] as? String
                ?: return Tool.Result.text("Missing required parameter: name")

            val value = blackboard!![name]
            if (value == null) {
                return Tool.Result.text("No object found with binding name '$name'")
            }

            val formatted = entryFormatter.format(value)
            val typeName = value::class.simpleName ?: value::class.java.name
            return Tool.Result.text("Description of $typeName '$name':\n$formatted")
        }
    }

    private fun createCountTool(): Tool = object : Tool {
        override val definition = Tool.Definition(
            name = "blackboard_count",
            description = "Count the number of objects of a given type in the blackboard",
            inputSchema = Tool.InputSchema.of(
                Tool.Parameter.string(
                    "typeName",
                    "The type name to count (e.g., 'User' or 'com.example.User')",
                    required = true
                )
            ),
        )

        override fun call(input: String): Tool.Result {
            val (blackboard, error) = getBlackboardOrError()
            if (error != null) return error

            val params = parseParams(input)
            val typeName = params["typeName"] as? String
                ?: return Tool.Result.text("Missing required parameter: typeName")

            val count = blackboard!!.objects.count { satisfiesType(it, typeName) }
            return Tool.Result.text("Found $count object(s) of type '$typeName' in blackboard")
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseParams(input: String): Map<String, Any?> {
        if (input.isBlank()) return emptyMap()
        return try {
            objectMapper.readValue<Map<String, Any?>>(input)
        } catch (e: Exception) {
            emptyMap()
        }
    }
}
