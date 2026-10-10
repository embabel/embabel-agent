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
package com.embabel.agent.api.tool.hitl

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolCallContext
import org.jetbrains.annotations.ApiStatus
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

/**
 * Records the user's verdict on the open proposal of a [ConfirmationGuardedTool].
 * On acceptance it runs the guarded tool with the confirmed arguments and returns its result.
 *
 * Named `<prefix><guarded tool name>`, see [ConfirmationGuardOptions.verdictToolPrefix].
 * Its schema is fixed: `accepted` (required boolean) and `note` (optional string).
 */
@ApiStatus.Experimental
class ConfirmationVerdictTool internal constructor(
    private val guard: ConfirmationGuardedTool,
) : Tool {

    private val objectMapper = ObjectMapper()

    override val definition: Tool.Definition = Tool.Definition(
        name = guard.options.verdictToolPrefix + guard.definition.name,
        description = "Record the user's answer to the pending confirmation for '${guard.definition.name}'. " +
                "Call this only after the user has explicitly confirmed or declined in the conversation. " +
                "accepted=true runs '${guard.definition.name}' exactly as it was proposed; accepted=false cancels it.",
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter(
                name = "accepted",
                type = Tool.ParameterType.BOOLEAN,
                description = "true if the user confirmed, false if the user declined",
                required = true,
            ),
            Tool.Parameter(
                name = "note",
                type = Tool.ParameterType.STRING,
                description = "Optional: what the user said, in their own words",
                required = false,
            ),
        ),
    )

    override fun call(input: String): Tool.Result = call(input, ToolCallContext.EMPTY)

    override fun call(input: String, context: ToolCallContext): Tool.Result {
        val node = try {
            objectMapper.readTree(input)
        } catch (e: JacksonException) {
            return Tool.Result.error("Invalid input for '${definition.name}': ${e.message}", e)
        }
        val acceptedNode = node?.get("accepted")
        if (acceptedNode == null || !acceptedNode.isBoolean) {
            return Tool.Result.error("'${definition.name}' requires a boolean 'accepted' argument.")
        }
        val note = node.get("note")?.takeIf { it.isTextual }?.asString()
        return guard.recordVerdict(acceptedNode.asBoolean(), note, context)
    }
}
