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
package com.embabel.agent.spi.validation.action

import com.embabel.common.core.validation.ValidationResult

/**
 * When an action's inputs or output are validated.
 */
enum class ActionValidationPhase {
    INPUT,
    OUTPUT,
}

/**
 * A failed validation of an action's inputs or output.
 *
 * @param actionName name of the action whose inputs or output failed validation
 * @param phase whether the inputs or the output failed
 * @param results results of all validators that ran in this phase
 * @param consecutiveFailures how many times in a row this action has failed
 * validation in this phase, including this failure (1 for the first failure)
 */
data class ActionValidationFailure(
    val actionName: String,
    val phase: ActionValidationPhase,
    val results: List<ValidationResult>,
    val consecutiveFailures: Int,
) {

    init {
        require(consecutiveFailures >= 1) { "consecutiveFailures must be at least 1" }
    }
}

/**
 * What the agent process does after an action fails validation.
 */
sealed class ActionValidationOutcome {

    /**
     * Log the failure and carry on: run the action, or publish its output.
     */
    data object Continue : ActionValidationOutcome()

    /**
     * Skip the action and ask the planner to replan.
     *
     * @param publishOutput for the output phase, whether the invalid output
     * is still bound to the blackboard. Ignored for the input phase.
     */
    data class Replan @JvmOverloads constructor(
        val publishOutput: Boolean = false,
    ) : ActionValidationOutcome()

    /**
     * Fail the action with [ActionValidationException].
     */
    data object Fail : ActionValidationOutcome()

    /**
     * Terminate the agent process.
     */
    data object TerminateAgent : ActionValidationOutcome()
}

/**
 * Decides what happens when an action's inputs or output fail validation.
 *
 * The default is [com.embabel.agent.spi.support.validation.action.DefaultActionValidationErrorPolicy].
 * Provide your own implementation to change any outcome.
 */
fun interface ActionValidationErrorPolicy {

    fun onValidationFailure(failure: ActionValidationFailure): ActionValidationOutcome
}
