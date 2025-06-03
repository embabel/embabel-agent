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
package com.embabel.agent.spi

import com.embabel.agent.event.ActionExecutionStartEvent
import com.embabel.agent.event.ToolCallRequestEvent
import com.embabel.common.core.types.Timestamped
import java.time.Duration
import java.time.Instant

/**
 * When should an action run?
 */
sealed interface ActionExecutionSchedule : Timestamped

/**
 * No delay
 */
data class ProntoActionExecutionSchedule(
    override val timestamp: Instant = Instant.now(),
) : ActionExecutionSchedule

/**
 * Run after a given delay
 */
data class DelayedActionExecutionSchedule(
    val delay: Duration,
    override val timestamp: Instant = Instant.now(),
) : ActionExecutionSchedule

/**
 * Run at the given time in the future
 */
data class ScheduledActionExecutionSchedule(
    val till: Instant,
    override val timestamp: Instant = Instant.now(),
) : ActionExecutionSchedule


data class ToolCallSchedule(
    val delay: Duration = Duration.ZERO,
    override val timestamp: Instant = Instant.now(),
) : Timestamped


/**
 * Schedules operations for an AgentProcess.
 */
interface OperationScheduler {

    fun scheduleAction(
        actionExecutionStartEvent: ActionExecutionStartEvent,
    ): ActionExecutionSchedule

    fun scheduleToolCall(
        functionCallRequestEvent: ToolCallRequestEvent,
    ): ToolCallSchedule

    companion object {

        /**
         * No delay
         */
        val PRONTO: OperationScheduler = ProntoOperationScheduler
    }
}

private object ProntoOperationScheduler : OperationScheduler {

    override fun scheduleAction(actionExecutionStartEvent: ActionExecutionStartEvent): ActionExecutionSchedule =
        ProntoActionExecutionSchedule()

    override fun scheduleToolCall(functionCallRequestEvent: ToolCallRequestEvent): ToolCallSchedule =
        ToolCallSchedule()
}
