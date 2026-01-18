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
package com.embabel.agent.spi.support.guardrails


import com.embabel.agent.core.Blackboard
import com.embabel.agent.api.validation.guardrails.AssistantMessageGuardRail
import com.embabel.agent.api.validation.guardrails.GuardRailViolationException
import com.embabel.agent.api.validation.guardrails.UserInputGuardRail
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.chat.AssistantMessage
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.core.validation.ValidationResult
import com.embabel.common.core.validation.ValidationSeverity
import com.embabel.common.util.loggerFor

private val logger = loggerFor<Any>()

/**
 * Validates user input using configured guardrails from interaction options.
 */
internal fun validateUserInput(
    springAiPrompt: String,
    interaction: LlmInteraction,
    blackboard: Blackboard?
) {
    val userInputGuards = interaction.options.guardRailConfig.guards
        .filterIsInstance<UserInputGuardRail>()

    if (userInputGuards.isEmpty()) return

    val effectiveBlackboard = blackboard ?: InMemoryBlackboard()
    userInputGuards.forEach { guard ->
        val result = guard.validate(springAiPrompt, effectiveBlackboard)
        handleValidationResult(guard.name, result)
    }
}

/**
 * Validates String assistant response using configured guardrails from interaction options.
 */
internal fun validateAssistantResponse(
    response: String,
    interaction: LlmInteraction,
    blackboard: Blackboard?
) {
    val assistantGuards = interaction.options.guardRailConfig.guards
        .filterIsInstance<AssistantMessageGuardRail>()

    if (assistantGuards.isEmpty()) return

    val effectiveBlackboard = blackboard ?: InMemoryBlackboard()
    assistantGuards.forEach { guard ->
        val result = guard.validate(response, effectiveBlackboard)
        handleValidationResult(guard.name, result)
    }
}

/**
 * Validates AssistantMessage response using configured guardrails from interaction options.
 */
internal fun validateAssistantResponse(
    response: AssistantMessage,
    interaction: LlmInteraction,
    blackboard: Blackboard?
) {
    val assistantGuards = interaction.options.guardRailConfig.guards
        .filterIsInstance<AssistantMessageGuardRail>()

    if (assistantGuards.isEmpty()) return

    val effectiveBlackboard = blackboard ?: InMemoryBlackboard()
    assistantGuards.forEach { guard ->
        val result = guard.validate(response, effectiveBlackboard)
        handleValidationResult(guard.name, result)
    }
}

/**
 * Validates ThinkingResponse using configured guardrails from interaction options.
 */
internal fun validateAssistantResponse(
    response: ThinkingResponse<*>,
    interaction: LlmInteraction,
    blackboard: Blackboard?
) {
    val assistantGuards = interaction.options.guardRailConfig.guards
        .filterIsInstance<AssistantMessageGuardRail>()

    if (assistantGuards.isEmpty()) return

    val effectiveBlackboard = blackboard ?: InMemoryBlackboard()
    assistantGuards.forEach { guard ->
        val result = guard.validate(response, effectiveBlackboard)
        handleValidationResult(guard.name, result)
    }
}

/**
 * Handles validation results and throws exceptions for critical violations.
 */
private fun handleValidationResult(guardName: String, result: ValidationResult) {
    val severityPriority = mapOf(
        ValidationSeverity.INFO to 1,
        ValidationSeverity.WARNING to 2,
        ValidationSeverity.ERROR to 3,
        ValidationSeverity.CRITICAL to 4
    )
    val highestSeverity = result.errors.maxByOrNull { severityPriority[it.severity] ?: 0 }?.severity
        ?: ValidationSeverity.INFO

    when (highestSeverity) {
        ValidationSeverity.INFO -> {
            if (result.errors.isNotEmpty()) {
                logger.info("GuardRail '{}' info: {}", guardName,
                    result.errors.joinToString("; ") { it.message })
            }
        }
        ValidationSeverity.WARNING -> {
            logger.warn("GuardRail '{}' warning: {}", guardName,
                result.errors.joinToString("; ") { it.message })
        }
        ValidationSeverity.ERROR -> {
            logger.error("GuardRail '{}' error: {}", guardName,
                result.errors.joinToString("; ") { it.message })
        }
        ValidationSeverity.CRITICAL -> {
            throw GuardRailViolationException(
                guard = guardName,
                violation = result.errors.joinToString("; ") { it.message },
                severity = highestSeverity
            )
        }
    }
}
