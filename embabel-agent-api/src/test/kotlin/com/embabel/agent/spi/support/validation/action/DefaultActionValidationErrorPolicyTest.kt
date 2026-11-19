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
import com.embabel.agent.spi.validation.action.ActionValidationPhase
import com.embabel.common.core.validation.ValidationError
import com.embabel.common.core.validation.ValidationResult
import com.embabel.common.core.validation.ValidationSeverity
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.test.assertEquals

class DefaultActionValidationErrorPolicyTest {

    private val policy = DefaultActionValidationErrorPolicy()

    private fun result(vararg severities: ValidationSeverity) = ValidationResult(
        isValid = severities.none { it == ValidationSeverity.ERROR || it == ValidationSeverity.CRITICAL },
        errors = severities.map { ValidationError(code = it.name, message = "msg", severity = it) },
    )

    private fun failure(
        phase: ActionValidationPhase,
        vararg results: ValidationResult,
        consecutiveFailures: Int = 1,
    ) = ActionValidationFailure(
        actionName = "action",
        phase = phase,
        results = results.toList(),
        consecutiveFailures = consecutiveFailures,
    )

    @Nested
    inner class Matrix {

        @ParameterizedTest
        @EnumSource(ActionValidationPhase::class)
        fun `no errors continues`(phase: ActionValidationPhase) {
            assertEquals(
                ActionValidationOutcome.Continue,
                policy.onValidationFailure(failure(phase, ValidationResult.VALID)),
            )
        }

        @ParameterizedTest
        @EnumSource(ActionValidationPhase::class)
        fun `INFO and WARNING continue`(phase: ActionValidationPhase) {
            assertEquals(
                ActionValidationOutcome.Continue,
                policy.onValidationFailure(failure(phase, result(ValidationSeverity.INFO, ValidationSeverity.WARNING))),
            )
        }

        @ParameterizedTest
        @EnumSource(ActionValidationPhase::class)
        fun `ERROR replans without publishing output`(phase: ActionValidationPhase) {
            assertEquals(
                ActionValidationOutcome.Replan(publishOutput = false),
                policy.onValidationFailure(failure(phase, result(ValidationSeverity.ERROR))),
            )
        }

        @ParameterizedTest
        @EnumSource(ActionValidationPhase::class)
        fun `CRITICAL terminates the agent`(phase: ActionValidationPhase) {
            assertEquals(
                ActionValidationOutcome.TerminateAgent,
                policy.onValidationFailure(failure(phase, result(ValidationSeverity.CRITICAL))),
            )
        }

        @Test
        fun `highest severity across all results decides`() {
            val outcome = policy.onValidationFailure(
                failure(
                    ActionValidationPhase.INPUT,
                    result(ValidationSeverity.WARNING),
                    result(ValidationSeverity.CRITICAL),
                    result(ValidationSeverity.ERROR),
                )
            )
            assertEquals(ActionValidationOutcome.TerminateAgent, outcome)
        }
    }

    @Nested
    inner class RepeatedFailures {

        @Test
        fun `second consecutive ERROR fails instead of replanning`() {
            val outcome = policy.onValidationFailure(
                failure(ActionValidationPhase.INPUT, result(ValidationSeverity.ERROR), consecutiveFailures = 2)
            )
            assertEquals(ActionValidationOutcome.Fail, outcome)
        }

        @Test
        fun `maxReplans is configurable`() {
            val lenient = DefaultActionValidationErrorPolicy(maxReplans = 3)
            val error = result(ValidationSeverity.ERROR)
            assertEquals(
                ActionValidationOutcome.Replan(),
                lenient.onValidationFailure(failure(ActionValidationPhase.OUTPUT, error, consecutiveFailures = 3)),
            )
            assertEquals(
                ActionValidationOutcome.Fail,
                lenient.onValidationFailure(failure(ActionValidationPhase.OUTPUT, error, consecutiveFailures = 4)),
            )
        }

        @Test
        fun `maxReplans of zero fails on the first ERROR`() {
            val strict = DefaultActionValidationErrorPolicy(maxReplans = 0)
            assertEquals(
                ActionValidationOutcome.Fail,
                strict.onValidationFailure(failure(ActionValidationPhase.INPUT, result(ValidationSeverity.ERROR))),
            )
        }

        @Test
        fun `repeated CRITICAL still terminates`() {
            val outcome = policy.onValidationFailure(
                failure(ActionValidationPhase.INPUT, result(ValidationSeverity.CRITICAL), consecutiveFailures = 5)
            )
            assertEquals(ActionValidationOutcome.TerminateAgent, outcome)
        }

        @Test
        fun `repeated WARNING still continues`() {
            val outcome = policy.onValidationFailure(
                failure(ActionValidationPhase.INPUT, result(ValidationSeverity.WARNING), consecutiveFailures = 5)
            )
            assertEquals(ActionValidationOutcome.Continue, outcome)
        }
    }

    @Nested
    inner class Arguments {

        @Test
        fun `negative maxReplans is rejected`() {
            assertThrows<IllegalArgumentException> { DefaultActionValidationErrorPolicy(maxReplans = -1) }
        }

        @Test
        fun `consecutiveFailures below one is rejected`() {
            assertThrows<IllegalArgumentException> {
                failure(ActionValidationPhase.INPUT, result(ValidationSeverity.ERROR), consecutiveFailures = 0)
            }
        }
    }

    @Test
    fun `custom policy can override the matrix`() {
        val alwaysContinue = ActionValidationErrorPolicy { ActionValidationOutcome.Continue }
        assertEquals(
            ActionValidationOutcome.Continue,
            alwaysContinue.onValidationFailure(failure(ActionValidationPhase.OUTPUT, result(ValidationSeverity.CRITICAL))),
        )
    }
}
