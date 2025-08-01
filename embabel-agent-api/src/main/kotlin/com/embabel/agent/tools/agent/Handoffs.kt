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
package com.embabel.agent.tools.agent

import com.embabel.agent.api.common.autonomy.Autonomy
import com.embabel.agent.core.ToolCallbackPublisher
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.ai.tool.ToolCallback

/**
 * Handoffs to local agents.
 */
class Handoffs(
    autonomy: Autonomy,
    objectMapper: ObjectMapper,
    val inputTypes: List<Class<*>>,
    applicationName: String,
) : ToolCallbackPublisher {

    private val goalToolCallbackPublisher = PerGoalToolCallbackPublisher(
        autonomy = autonomy,
        objectMapper = objectMapper,
        applicationName = applicationName,
        textCommunicator = PromptedTextCommunicator,
    )

    override val toolCallbacks: List<ToolCallback>
        get() = goalToolCallbackPublisher.goalTools(remoteOnly = false)
            .filter { goalToolCallback ->
                inputTypes.any { inputType ->
                    goalToolCallback.goal.export.startingInputTypes.any { it.isAssignableFrom(inputType) }
                }
            }
}
