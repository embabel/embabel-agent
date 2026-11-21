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
package com.embabel.agent.api.validation.action

import com.embabel.agent.core.NonRetryable
import com.embabel.agent.spi.validation.action.ActionValidationFailure
import com.embabel.agent.spi.validation.action.ActionValidationPhase
import com.embabel.agent.spi.common.LlmRetryDecision
import com.embabel.common.core.validation.ValidationError
import com.embabel.common.core.validation.ValidationResult
import com.embabel.common.core.validation.ValidationSeverity
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ActionValidationExceptionTest {

    private val failure = ActionValidationFailure(
        actionName = "extractPerson",
        phase = ActionValidationPhase.INPUT,
        results = listOf(
            ValidationResult(
                isValid = false,
                errors = listOf(
                    ValidationError(code = "age", message = "must be positive", severity = ValidationSeverity.ERROR),
                    ValidationError(code = "name", message = "is blank", severity = ValidationSeverity.ERROR),
                ),
            )
        ),
        consecutiveFailures = 2,
    )

    @Test
    fun `is not retryable`() {
        val e = ActionValidationException(failure)
        assertIs<NonRetryable>(e)
        assertFalse(LlmRetryDecision.isRetryable(e))
    }

    @Test
    fun `message names action, phase and errors`() {
        assertEquals(
            "Action 'extractPerson' failed input validation: age: must be positive; name: is blank",
            ActionValidationException(failure).message,
        )
    }

    @Test
    fun `keeps the failure`() {
        assertEquals(failure, ActionValidationException(failure).failure)
    }
}
