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

import com.embabel.agent.api.channel.MessageOutputChannelEvent
import com.embabel.agent.core.AgentProcess
import com.embabel.chat.AssistantMessage
import org.slf4j.LoggerFactory

/**
 * Tool that allows an LLM to send a persistent message to the user.
 *
 * Unlike the `progress` tool (which shows transient status updates),
 * `communicate` sends a permanent chat message that appears as an
 * assistant bubble. Use this to report results, share links (e.g., PR URLs),
 * or inform the user of important outcomes.
 *
 * Routes messages through the current [AgentProcess] output channel.
 */
object CommunicateTool {

    private val logger = LoggerFactory.getLogger(CommunicateTool::class.java)

    const val NAME = "communicate"

    fun create(): Tool = Tool.of(
        name = NAME,
        description = "Send a permanent message to the user. " +
            "Use this to report results, share links (e.g., PR URLs), " +
            "or inform the user of important outcomes. " +
            "Unlike progress, this creates a visible chat message.",
        inputSchema = Tool.InputSchema.of(
            Tool.Parameter.string("message", "The message to send to the user"),
        ),
    ) { input ->
        val parsed = com.fasterxml.jackson.databind.ObjectMapper().readTree(input)
        val message = parsed.get("message")?.asText()
            ?: return@of Tool.Result.error("Missing 'message' parameter")

        val process = AgentProcess.get()
        if (process == null) {
            logger.warn("communicate tool called outside of an agent process — ignoring: {}", message)
            return@of Tool.Result.text("Message noted (no active process to deliver it).")
        }

        process.processOptions.outputChannel.send(
            MessageOutputChannelEvent(
                processId = process.id,
                message = AssistantMessage(content = message),
            )
        )

        Tool.Result.text("Message sent to user.")
    }
}
