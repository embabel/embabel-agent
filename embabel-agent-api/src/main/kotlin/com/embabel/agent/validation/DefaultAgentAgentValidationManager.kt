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
package com.embabel.agent.validation

import com.embabel.agent.core.AgentScope
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service


/**
 * Default implementation of [AgentValidationManager] that coordinates multiple [AgentValidator]s.
 *
 * Runs all configured validators on the provided [AgentScope] and aggregates their results,
 * collecting and reporting all validation errors or warnings.
 *
 * Supports both summary validation and detailed per-validator results.
 * Useful for extensible, modular validation pipelines where multiple agent checks
 * (such as structure, method signatures, and path-to-goal validation) are required.
 */
@Service
class DefaultAgentValidationManager(
    private val validators: List<AgentValidator>
) : AgentValidationManager {

    private val logger = LoggerFactory.getLogger(DefaultAgentValidationManager::class.java)

    override fun validate(agentScope: AgentScope): ValidationResult {
        val allErrors = validators.flatMap { validator ->
            validator.validate(agentScope).errors
        }

        if (allErrors.isNotEmpty()) {
            logger.error("Validation failed with ${allErrors.size} errors:")
            allErrors.forEach { error ->
                logger.error("- ${error.code}: ${error.message}")
            }
        } else {
            logger.debug("Validation passed for agent ${agentScope.name}")
        }

        return ValidationResult(allErrors.isEmpty(), allErrors)
    }

    override fun validateWithDetails(agentScope: AgentScope): DetailedValidationResult {
        val validationResults = validators.associateWith { validator ->
            validator.validate(agentScope)
        }
        return DetailedValidationResult(
            results = validationResults,
            isValid = validationResults.values.all { it.isValid }
        )
    }
}
