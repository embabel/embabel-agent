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
package com.embabel.agent.api.validation


import com.embabel.agent.core.Blackboard
import com.embabel.common.core.validation.ValidationResult

/**
 * Base validation framework for agent API use.
 * Generic interface supporting validation of different content types.
 *
 * This interface serves as the foundation for all agent-specific validation,
 * including guardrails, and action validators.
 */
interface ContentValidator<T> {

    /**
     * Human-readable name for this validator.
     * Used for logging, error reporting, and configuration.
     */
    val name: String

    /**
     * Description of what this validator checks.
     * Used for documentation and debugging purposes.
     */
    val description: String

    /**
     * Validate the given input within the provided blackboard context.
     *
     * @param input the content to validate
     * @param blackboard the blackboard providing access to all available objects
     * @return validation result indicating success or failure with details
     */
    fun validate(input: T, blackboard: Blackboard): ValidationResult
}
