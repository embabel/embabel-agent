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
import com.embabel.agent.api.validation.guardrails.GuardRail
import com.embabel.agent.api.validation.guardrails.GuardRailViolationException
import com.embabel.agent.api.validation.guardrails.UserInputGuardRail
import com.embabel.agent.core.support.LlmInteraction
import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.core.validation.ValidationResult
import com.embabel.common.core.validation.ValidationSeverity
import com.embabel.common.util.loggerFor

private val logger = loggerFor<Any>()

/**
 * Internal helper for user input validation that handles common guardrail logic.
 * Merges global guardrails from registry with interaction-specific guardrails.
 */
private fun validateUserInputInternal(
    interaction: LlmInteraction,
    blackboard: Blackboard?,
    validator: (UserInputGuardRail, Blackboard) -> ValidationResult
) {
    val interactionGuards = interaction.guardRails
        .filterIsInstance<UserInputGuardRail>()

    val globalGuards = GlobalGuardRailsRegistry.getUserInputGuardRails()

    // Merge global and interaction-specific guardrails, removing duplicates by class
    val effectiveGuards = (globalGuards + interactionGuards).distinctBy { it::class }

    if (effectiveGuards.isEmpty()) return

    val effectiveBlackboard = blackboard ?: InMemoryBlackboard()
    effectiveGuards.forEach { guard ->
        val result = validator(guard, effectiveBlackboard)
        handleValidationResult(guard, result)
    }
}

/**
 * Validates user input using configured guardrails from interaction options.
 */
internal fun validateUserInput(
    promptAsString: String,
    interaction: LlmInteraction,
    blackboard: Blackboard?
) = validateUserInputInternal(interaction, blackboard) { guard, bb ->
    guard.validate(promptAsString, bb)
}

/**
 * Validates user input from a list of user messages using configured guardrails.
 *
 * This overload passes the full list of user messages to each guardrail,
 * allowing guardrails to customize message combining via [UserInputGuardRail.combineMessages]
 * or implement conversation-aware validation by overriding [UserInputGuardRail.validate].
 */
internal fun validateUserInput(
    userMessages: List<UserMessage>,
    interaction: LlmInteraction,
    blackboard: Blackboard?
) = validateUserInputInternal(interaction, blackboard) { guard, bb ->
    guard.validate(userMessages, bb)
}

/**
 * Internal helper for assistant response validation that handles common guardrail logic.
 * Merges global guardrails from registry with interaction-specific guardrails.
 */
private fun validateAssistantInternal(
    interaction: LlmInteraction,
    blackboard: Blackboard?,
    validator: (AssistantMessageGuardRail, Blackboard) -> ValidationResult
) {
    val interactionGuards = interaction.guardRails
        .filterIsInstance<AssistantMessageGuardRail>()

    val globalGuards = GlobalGuardRailsRegistry.getAssistantMessageGuardRails()

    // Merge global and interaction-specific guardrails, removing duplicates by class
    val effectiveGuards = (globalGuards + interactionGuards).distinctBy { it::class }

    if (effectiveGuards.isEmpty()) return

    val effectiveBlackboard = blackboard ?: InMemoryBlackboard()
    effectiveGuards.forEach { guard ->
        val result = validator(guard, effectiveBlackboard)
        handleValidationResult(guard, result)
    }
}

/**
 * Validates String assistant response using configured guardrails from interaction options and global registry.
 */
internal fun validateAssistantResponse(
    response: String,
    interaction: LlmInteraction,
    blackboard: Blackboard?
) = validateAssistantInternal(interaction, blackboard) { guard, bb ->
    guard.validate(response, bb)
}

/**
 * Validates AssistantMessage response using configured guardrails from interaction options and global registry.
 */
internal fun validateAssistantResponse(
    response: AssistantMessage,
    interaction: LlmInteraction,
    blackboard: Blackboard?
) = validateAssistantInternal(interaction, blackboard) { guard, bb ->
    guard.validate(response, bb)
}

/**
 * Validates ThinkingResponse using configured guardrails from interaction options and global registry.
 */
internal fun validateAssistantResponse(
    response: ThinkingResponse<*>,
    interaction: LlmInteraction,
    blackboard: Blackboard?
) = validateAssistantInternal(interaction, blackboard) { guard, bb ->
    guard.validate(response, bb)
}

/**
 * Handles validation results and throws exceptions for critical violations.
 */
private fun handleValidationResult(guard: GuardRail, result: ValidationResult) {
    val guardName = guard.name
    val guardClassName = guard::class.simpleName
    val guardIdentifier = guardClassName?.let { "$guardName ($it)" } ?: guardName

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
                logger.info("GuardRail '{}' info: {}", guardIdentifier,
                    result.errors.joinToString("; ") { it.message })
            }
        }
        ValidationSeverity.WARNING -> {
            logger.warn("GuardRail '{}' warning: {}", guardIdentifier,
                result.errors.joinToString("; ") { it.message })
        }
        ValidationSeverity.ERROR -> {
            logger.error("GuardRail '{}' error: {}", guardIdentifier,
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
