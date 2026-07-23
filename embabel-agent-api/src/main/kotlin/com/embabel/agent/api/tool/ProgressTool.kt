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
package com.embabel.agent.api.tool

import com.embabel.agent.api.channel.ProgressOutputChannelEvent
import com.embabel.agent.core.AgentProcess
import org.slf4j.LoggerFactory

/**
 * Tool that allows an LLM to report progress during long-running actions.
 * Routes messages through the current [AgentProcess] output channel.
 *
 * If no process is active on the current thread, logs a warning and returns
 * a soft error — the action can continue without interruption.
 */
object ProgressTool {

    private val logger = LoggerFactory.getLogger(ProgressTool::class.java)

    const val NAME = "progress"

    fun create(): Tool = Tool.of(
        name = NAME,
        description = "Report progress to the user. Call this during long-running work to show what you're doing.",
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string("status", "Short progress message describing what you're currently doing"),
        ),
    ) { input ->
        val parsed = com.fasterxml.jackson.databind.ObjectMapper().readTree(input)
        val status = parsed.get("status")?.asText()
            ?: return@of Tool.Result.error("Missing 'status' parameter")

        val process = AgentProcess.get()
        if (process == null) {
            logger.warn("progress tool called outside of an agent process — ignoring: {}", status)
            return@of Tool.Result.text("Progress noted (no active process to display it).")
        }

        process.processOptions.outputChannel.send(
            ProgressOutputChannelEvent(
                processId = process.id,
                message = status,
            )
        )

        Tool.Result.text("Progress reported: $status")
    }
}
