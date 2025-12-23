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
package com.embabel.agent.yml

import com.embabel.agent.core.Goal
import com.embabel.agent.core.IoBinding
import com.fasterxml.jackson.annotation.JsonPropertyDescription

/**
 * Serializable Goal data
 */
data class GoalDefinition(
    override val name: String,
    override val description: String,
    val outputTypeName: String,
    @param:JsonPropertyDescription("Type of step, must be 'goal'")
    override val stepType: String = "goal",
) : StepDefinition<Goal> {

    override fun emit(stepContext: StepContext): Goal {
        return Goal(
            description = description,
            name = name,
            inputs = setOf(IoBinding(PromptedActionDefinition.variableNameFor(outputTypeName), outputTypeName))
        )
    }
}
