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
package com.embabel.agent.skills.support

import com.embabel.agent.skills.spec.SkillMetadata

/**
 * Validator for Agent Skills according to the specification.
 *
 * @see <a href="https://agentskills.io/specification">Agent Skills Specification</a>
 */
interface SkillValidator {

    /**
     * Validate a skill against the Agent Skills specification.
     *
     * @param skill the skill to validate
     * @param directoryName optional parent directory name for directory match validation
     * @return validation result containing any errors
     */
    fun validate(
        skill: SkillMetadata,
        directoryName: String? = null,
    ): SkillValidationResult
}

/**
 * Result of skill validation.
 */
data class SkillValidationResult(
    val isValid: Boolean,
    val errors: List<SkillValidationError>,
) {
    companion object {
        val VALID = SkillValidationResult(isValid = true, errors = emptyList())
    }
}

/**
 * A validation error for a skill.
 */
data class SkillValidationError(
    val field: String,
    val message: String,
)

/**
 * Default implementation of [SkillValidator] according to the Agent Skills specification.
 *
 * Validation rules:
 * - `name`: Required, 1-64 chars, lowercase alphanumeric and hyphens only,
 *   cannot start/end with hyphen, no consecutive hyphens, must match directory name if provided
 * - `description`: Required, 1-1024 chars
 * - `compatibility`: Optional, 1-500 chars if provided
 *
 * @see <a href="https://agentskills.io/specification">Agent Skills Specification</a>
 */
class DefaultSkillValidator : SkillValidator {

    override fun validate(
        skill: SkillMetadata,
        directoryName: String?,
    ): SkillValidationResult {
        val errors = mutableListOf<SkillValidationError>()

        errors.addAll(validateName(skill.name, directoryName))
        errors.addAll(validateDescription(skill.description))
        skill.compatibility?.let { errors.addAll(validateCompatibility(it)) }

        return if (errors.isEmpty()) {
            SkillValidationResult.VALID
        } else {
            SkillValidationResult(isValid = false, errors = errors)
        }
    }

    private fun validateName(
        name: String,
        directoryName: String?,
    ): List<SkillValidationError> {
        val errors = mutableListOf<SkillValidationError>()

        if (name.isEmpty()) {
            errors.add(SkillValidationError("name", "Name is required"))
            return errors
        }

        if (name.length > MAX_NAME_LENGTH) {
            errors.add(
                SkillValidationError(
                    "name",
                    "Name must be at most $MAX_NAME_LENGTH characters, was ${name.length}"
                )
            )
        }

        if (!NAME_PATTERN.matches(name)) {
            errors.add(
                SkillValidationError(
                    "name",
                    "Name must contain only lowercase alphanumeric characters and hyphens"
                )
            )
        }

        if (name.startsWith("-")) {
            errors.add(SkillValidationError("name", "Name must not start with a hyphen"))
        }

        if (name.endsWith("-")) {
            errors.add(SkillValidationError("name", "Name must not end with a hyphen"))
        }

        if (name.contains("--")) {
            errors.add(SkillValidationError("name", "Name must not contain consecutive hyphens"))
        }

        if (directoryName != null && name != directoryName) {
            errors.add(
                SkillValidationError(
                    "name",
                    "Name must match parent directory name '$directoryName'"
                )
            )
        }

        return errors
    }

    private fun validateDescription(description: String): List<SkillValidationError> {
        val errors = mutableListOf<SkillValidationError>()

        if (description.isEmpty()) {
            errors.add(SkillValidationError("description", "Description is required"))
            return errors
        }

        if (description.length > MAX_DESCRIPTION_LENGTH) {
            errors.add(
                SkillValidationError(
                    "description",
                    "Description must be at most $MAX_DESCRIPTION_LENGTH characters, was ${description.length}"
                )
            )
        }

        return errors
    }

    private fun validateCompatibility(compatibility: String): List<SkillValidationError> {
        val errors = mutableListOf<SkillValidationError>()

        if (compatibility.isEmpty()) {
            errors.add(
                SkillValidationError(
                    "compatibility",
                    "Compatibility must not be empty if provided"
                )
            )
        }

        if (compatibility.length > MAX_COMPATIBILITY_LENGTH) {
            errors.add(
                SkillValidationError(
                    "compatibility",
                    "Compatibility must be at most $MAX_COMPATIBILITY_LENGTH characters, was ${compatibility.length}"
                )
            )
        }

        return errors
    }

    companion object {
        const val MAX_NAME_LENGTH = 64
        const val MAX_DESCRIPTION_LENGTH = 1024
        const val MAX_COMPATIBILITY_LENGTH = 500

        private val NAME_PATTERN = Regex("^[a-z0-9-]+$")
    }
}
