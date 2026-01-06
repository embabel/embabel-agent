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

import com.embabel.agent.api.event.AbstractAgentProcessEvent
import com.embabel.agent.core.AgentProcess

enum class StuckHandlingResultCode {
    REPLAN,
    NO_RESOLUTION,
}

class StuckHandlerResult(
    val message: String,
    val handler: StuckHandler?,
    val code: StuckHandlingResultCode,
    agentProcess: AgentProcess,
) : AbstractAgentProcessEvent(agentProcess)

/**
 * Attempts to resolve stuck processes
 */
fun interface StuckHandler {

    /**
     * Attempt to resolve a stuck agent process
     * Resolution will occur via side effects on AgentProcess
     */
    fun handleStuck(
        agentProcess: AgentProcess,
    ): StuckHandlerResult

    companion object {

        operator fun invoke(
            vararg handlers: StuckHandler,
        ): StuckHandler = MulticastStuckHandler(handlers.toList())
    }
}

/**
 * Try to resolve a stuck agent process by trying all handlers in order
 */
class MulticastStuckHandler(
    private val stuckHandlers: List<StuckHandler>,
) : StuckHandler {

    override fun handleStuck(agentProcess: AgentProcess): StuckHandlerResult {
        for (handler in stuckHandlers) {
            val result = handler.handleStuck(agentProcess)
            if (result.code != StuckHandlingResultCode.NO_RESOLUTION) {
                return result
            }
        }
        return StuckHandlerResult(
            message = "No stuck handler could resolve the issue: Tried ${stuckHandlers.joinToString(", ") { it::class.java.name }}",
            code = StuckHandlingResultCode.NO_RESOLUTION,
            agentProcess = agentProcess,
            handler = null,
        )
    }
}
