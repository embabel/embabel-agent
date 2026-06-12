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

import com.embabel.agent.api.event.ToolLoopStartEvent
import com.embabel.chat.Message
import io.micrometer.observation.Observation
import org.jetbrains.annotations.ApiStatus

/**
 * Thin context for the `embabel.tool_loop` span: wraps the [ToolLoopStartEvent] and the
 * [inputMessages] (the prompt, captured at start). [output] is set after the loop runs and read by
 * the convention at stop — the only mutable field, since the result is not on the start event.
 *
 * No cross-thread synchronization is needed: `observe{}` is synchronous, so [output] is written and
 * then read (by the convention at stop) on the same thread, with a happens-before from program order.
 */
@ApiStatus.Internal
class ToolLoopObservationContext(
    val startEvent: ToolLoopStartEvent,
    val inputMessages: List<Message>,
) : Observation.Context() {

    var output: Any? = null
}
