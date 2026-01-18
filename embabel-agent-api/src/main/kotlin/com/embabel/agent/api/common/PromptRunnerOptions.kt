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
package com.embabel.agent.api.common

import com.embabel.agent.api.validation.guardrails.GuardRail
import com.embabel.agent.api.validation.guardrails.GuardRailConfiguration

/**
 * Configuration options for PromptRunner that are not related to LLM settings.
 *
 * This data class follows the same immutable pattern as LlmOptions, providing
 * a centralized place for all PromptRunner configuration beyond LLM parameters.
 * Supports fluent configuration through `with*` methods that return new instances.
 */
data class PromptRunnerOptions(
    /**
     * Guardrail configuration for validation and safety checks.
     * Includes both input and output validation settings.
     */
    val guardRailConfig: GuardRailConfiguration = GuardRailConfiguration.NONE,

    // Future extensions:
    // val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT,
    // val timeoutConfig: TimeoutConfig = TimeoutConfig.DEFAULT,
    // val validationLevels: Set<ValidationLevel> = emptySet(),
) {

    /**
     * Create a new PromptRunnerOptions with the specified guardrail instances.
     *
     * @param guards the guardrail instances to configure
     * @return new PromptRunnerOptions instance with updated guardrail configuration
     */
    fun withGuards(vararg guards: GuardRail): PromptRunnerOptions =
        copy(guardRailConfig = guardRailConfig.withGuards(*guards))

    /**
     * Create a new PromptRunnerOptions with the specified guardrail configuration.
     *
     * @param config the complete guardrail configuration
     * @return new PromptRunnerOptions instance with updated configuration
     */
    fun withGuardRailConfig(config: GuardRailConfiguration): PromptRunnerOptions =
        copy(guardRailConfig = config)

    companion object {
        /**
         * Default PromptRunnerOptions with no special configuration.
         */
        val DEFAULT = PromptRunnerOptions()
    }
}
