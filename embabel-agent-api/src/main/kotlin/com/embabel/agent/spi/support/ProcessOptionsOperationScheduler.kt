/*
 * Copyright 2024-2025 Embabel Software, Inc.
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

import com.embabel.agent.core.Delay
import com.embabel.agent.event.ActionExecutionStartEvent
import com.embabel.agent.event.ToolCallRequestEvent
import com.embabel.agent.spi.ActionExecutionSchedule
import com.embabel.agent.spi.DelayedActionExecutionSchedule
import com.embabel.agent.spi.OperationScheduler
import com.embabel.agent.spi.ToolCallSchedule
import java.time.Duration

/**
 * Operation scheduler driven from process options
 */
class ProcessOptionsOperationScheduler(
    val operationDelays: Map<Delay, Long> = mapOf(
        Delay.NONE to 0L,
        Delay.MEDIUM to 400L,
        Delay.LONG to 2000L,
    ),
    val toolDelays: Map<Delay, Long> = mapOf(
        Delay.NONE to 0L,
        Delay.MEDIUM to 400L,
        Delay.LONG to 2000L,
    )
) : OperationScheduler {

    override fun scheduleAction(actionExecutionStartEvent: ActionExecutionStartEvent): ActionExecutionSchedule {
        return DelayedActionExecutionSchedule(
            Duration.ofMillis(
                operationDelays[actionExecutionStartEvent.agentProcess.processContext.processOptions.control.operationDelay]
                    ?: 0L,
            )
        )
    }

    override fun scheduleToolCall(functionCallRequestEvent: ToolCallRequestEvent): ToolCallSchedule {
        return ToolCallSchedule(
            delay = Duration.ofMillis(
                toolDelays[functionCallRequestEvent.agentProcess.processContext.processOptions.control.operationDelay]
                    ?: 0L,
            )
        )
    }


}
