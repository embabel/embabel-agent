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
package com.embabel.agent.api.event.progress

import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.channel.ProgressOutputChannelEvent
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.LlmRequestEvent
import com.embabel.agent.api.event.ToolCallRequestEvent

/**
 * Convenient event listener that highlights important events in the output channel.
 */
class OutputChannelHighlightingEventListener(
    private val outputChannel: OutputChannel,
    private val verbose: Boolean = true,
) : AgenticEventListener {

    override fun onProcessEvent(event: AgentProcessEvent) {
        when (event) {
            is ToolCallRequestEvent -> {
                var message = "🔧 ${event.tool}"
                if (verbose) {
                    message += " with input `${event.toolInput}`"
                }
                outputChannel.send(
                    ProgressOutputChannelEvent(
                        processId = event.processId,
                        message = message,
                    )
                )
            }

            is LlmRequestEvent<*> -> {
                val message = "Calling LLM ${event.llmService.name}"
                outputChannel.send(
                    ProgressOutputChannelEvent(
                        processId = event.processId,
                        message = message,
                    )
                )
            }
        }
    }
}
