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
package com.embabel.agent.api.event.observation

import com.embabel.agent.core.ActionStatusCode
import com.embabel.agent.core.AgentProcess
import com.embabel.plan.Action
import io.micrometer.observation.Observation
import org.jetbrains.annotations.ApiStatus

/**
 * Thin context for the `embabel.action` span: wraps the owning [AgentProcess] and the [Action].
 *
 * [statusCode] is set after the action runs and read by the convention at stop — the only mutable
 * field, since the outcome status is not known when the span opens. No cross-thread synchronization
 * is needed: `observe{}` is synchronous, so it is written and then read on the same thread, with a
 * happens-before from program order.
 */
@ApiStatus.Internal
class ActionObservationContext(
    val process: AgentProcess,
    val action: Action,
) : Observation.Context() {

    var statusCode: ActionStatusCode? = null

    override fun toString(): String =
        "ActionObservationContext(processId=${process.id}, action=${action.name}, statusCode=$statusCode)"
}
