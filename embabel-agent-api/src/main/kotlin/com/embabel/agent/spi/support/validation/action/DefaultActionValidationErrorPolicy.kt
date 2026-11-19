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
package com.embabel.agent.spi.support.validation.action

import com.embabel.agent.spi.validation.action.ActionValidationErrorPolicy
import com.embabel.agent.spi.validation.action.ActionValidationFailure
import com.embabel.agent.spi.validation.action.ActionValidationOutcome
import com.embabel.common.core.validation.ValidationSeverity

/**
 * Default [ActionValidationErrorPolicy]. The outcome follows the highest severity
 * across all validation results:
 *
 * | Highest severity        | Outcome                                       |
 * |-------------------------|-----------------------------------------------|
 * | none, INFO or WARNING   | [ActionValidationOutcome.Continue]            |
 * | ERROR                   | [ActionValidationOutcome.Replan], output not published |
 * | CRITICAL                | [ActionValidationOutcome.TerminateAgent]      |
 *
 * An ERROR that repeats more than [maxReplans] times in a row for the same action
 * and phase becomes [ActionValidationOutcome.Fail]. Without this cap, an action that
 * is the only way to reach the goal would be replanned forever.
 *
 * @param maxReplans how many consecutive ERROR failures replan before failing
 */
class DefaultActionValidationErrorPolicy @JvmOverloads constructor(
    private val maxReplans: Int = DEFAULT_MAX_REPLANS,
) : ActionValidationErrorPolicy {

    init {
        require(maxReplans >= 0) { "maxReplans must not be negative" }
    }

    override fun onValidationFailure(failure: ActionValidationFailure): ActionValidationOutcome =
        when (highestSeverity(failure)) {
            null, ValidationSeverity.INFO, ValidationSeverity.WARNING -> ActionValidationOutcome.Continue
            ValidationSeverity.ERROR ->
                if (failure.consecutiveFailures > maxReplans) ActionValidationOutcome.Fail
                else ActionValidationOutcome.Replan(publishOutput = false)

            ValidationSeverity.CRITICAL -> ActionValidationOutcome.TerminateAgent
        }

    private fun highestSeverity(failure: ActionValidationFailure): ValidationSeverity? =
        failure.results
            .mapNotNull { it.getHighestSeverity() }
            .maxByOrNull { SEVERITY_RANK.getValue(it) }

    companion object {

        const val DEFAULT_MAX_REPLANS = 1

        private val SEVERITY_RANK = mapOf(
            ValidationSeverity.INFO to 1,
            ValidationSeverity.WARNING to 2,
            ValidationSeverity.ERROR to 3,
            ValidationSeverity.CRITICAL to 4,
        )
    }
}
