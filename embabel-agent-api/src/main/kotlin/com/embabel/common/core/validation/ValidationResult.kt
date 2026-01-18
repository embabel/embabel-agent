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
package com.embabel.common.core.validation

data class ValidationResult(
    val isValid: Boolean,
    val errors: List<ValidationError>,
) {

    /**
     * Returns the highest severity level among all errors, or null if no errors exist.
     * Severity is ranked: CRITICAL > ERROR > WARNING > INFO
     */
    fun getHighestSeverity(): ValidationSeverity? {
        val severityPriority = mapOf(
            ValidationSeverity.INFO to 1,
            ValidationSeverity.WARNING to 2,
            ValidationSeverity.ERROR to 3,
            ValidationSeverity.CRITICAL to 4
        )
        return errors.maxByOrNull { severityPriority[it.severity] ?: 0 }?.severity
    }

    companion object {
        val VALID = ValidationResult(
            isValid = true,
            errors = emptyList(),
        )
    }
}
