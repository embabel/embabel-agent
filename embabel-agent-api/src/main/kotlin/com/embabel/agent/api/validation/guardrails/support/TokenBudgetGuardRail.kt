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
package com.embabel.agent.api.validation.guardrails.support

import com.embabel.agent.api.validation.guardrails.UserInputGuardRail
import com.embabel.agent.core.Blackboard
import com.embabel.common.ai.model.TokenCountEstimator
import com.embabel.common.core.validation.ValidationError
import com.embabel.common.core.validation.ValidationResult
import com.embabel.common.core.validation.ValidationSeverity
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Experimental
class TokenBudgetGuardRail @JvmOverloads constructor(
    private val tokenCountEstimator: TokenCountEstimator<String>,
    private val maxTokens: Int,
    private val severity: ValidationSeverity = ValidationSeverity.WARNING,
) : UserInputGuardRail {

    override val name: String = "TokenBudgetGuardRail"

    override val description: String = "Validates that user input does not exceed $maxTokens estimated tokens"

    override fun validate(input: String, blackboard: Blackboard): ValidationResult {
        val estimated = tokenCountEstimator.estimate(input)
        if (estimated <= maxTokens) return ValidationResult.VALID
        return ValidationResult(
            isValid = false,
            errors = listOf(
                ValidationError(
                    code = "TOKEN_BUDGET_EXCEEDED",
                    message = "Input estimated at ~$estimated tokens exceeds budget of $maxTokens",
                    severity = severity,
                ),
            ),
        )
    }
}
