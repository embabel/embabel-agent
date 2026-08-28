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
package com.embabel.agent.spi.support

import com.embabel.agent.api.event.ActionExecutionStartEvent
import com.embabel.agent.api.event.ToolCallRequestEvent
import com.embabel.agent.core.Delay
import com.embabel.agent.core.DelayPolicy
import com.embabel.agent.spi.ActionExecutionSchedule
import com.embabel.agent.spi.DelayedActionExecutionSchedule
import com.embabel.agent.spi.OperationScheduler
import com.embabel.agent.spi.ToolCallSchedule
import org.slf4j.LoggerFactory

/**
 * Operation scheduler driven from process options.
 * Action-level [DelayPolicy] takes precedence over the process-level fallback.
 */
class ProcessOptionsOperationScheduler(
    @Deprecated("Unused: delay is read directly from DelayPolicy.duration")
    val operationDelays: Map<Delay, Long> = mapOf(
        Delay.NONE to 0L,
        Delay.MEDIUM to 400L,
        Delay.LONG to 2000L,
    ),
    @Deprecated("Unused: delay is read directly from DelayPolicy.duration")
    val toolDelays: Map<Delay, Long> = mapOf(
        Delay.NONE to 0L,
        Delay.MEDIUM to 400L,
        Delay.LONG to 2000L,
    ),
) : OperationScheduler {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Resolves the delay for an action using a two-level priority chain:
     * 1. Action QoS delay — resolved by [com.embabel.agent.api.annotation.support.DefaultActionQosProvider] from
     *    [@Action][com.embabel.agent.api.annotation.Action] or [@Agent][com.embabel.agent.api.annotation.Agent]
     *    annotations (action-level wins over agent-level).
     * 2. Process-level fallback from [com.embabel.agent.core.ProcessControl.operationDelayPolicy].
     *
     * A [DelayPolicy.None] QoS delay is treated as "not set" and falls through to the process fallback.
     */
    override fun scheduleAction(actionExecutionStartEvent: ActionExecutionStartEvent): ActionExecutionSchedule {
        val processControl = actionExecutionStartEvent.agentProcess.processContext.processOptions.processControl
        val actionQosDelay = actionExecutionStartEvent.action.qos.delayPolicy.takeIf { it != DelayPolicy.None }
        val delay = actionQosDelay ?: processControl.operationDelayPolicy
        if (delay != DelayPolicy.None) {
            val source = if (actionQosDelay != null) "qos" else "process"
            logger.debug(
                "Scheduling {}ms delay for action {} (source: {})",
                delay.millis,
                actionExecutionStartEvent.action.name,
                source,
            )
        }
        return DelayedActionExecutionSchedule(delay.duration)
    }

    override fun scheduleToolCall(functionCallRequestEvent: ToolCallRequestEvent): ToolCallSchedule {
        return ToolCallSchedule(
            delay = functionCallRequestEvent.agentProcess.processContext.processOptions.processControl.toolDelayPolicy.duration,
        )
    }
}
