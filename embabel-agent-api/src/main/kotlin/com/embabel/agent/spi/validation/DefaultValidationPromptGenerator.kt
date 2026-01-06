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
package com.embabel.agent.spi.validation

import jakarta.validation.ConstraintViolation
import jakarta.validation.Validator

class DefaultValidationPromptGenerator : ValidationPromptGenerator {

    override fun generateRequirementsPrompt(
        validator: Validator,
        outputClass: Class<*>,
    ): String {
        val descriptor = validator.getConstraintsForClass(outputClass)
        val requirements = mutableListOf<String>()

        descriptor.constrainedProperties.forEach { propertyDescriptor ->
            val propertyName = propertyDescriptor.propertyName
            val constraints = propertyDescriptor.constraintDescriptors

            constraints.forEach { constraint ->
                val annotationType = constraint.annotation.annotationClass.simpleName
                val message = constraint.messageTemplate

                requirements.add("- Field '$propertyName': $annotationType constraint ($message)")
            }
        }

        return if (requirements.isEmpty()) {
            "No validation constraints defined."
        } else {
            "Validation Requirements:\n" + requirements.joinToString("\n")
        }
    }

    /**
     * (b) Generate a string based on actual constraint violations
     * This describes what went wrong after validation
     */
    override fun <T> generateViolationsReport(violations: Set<ConstraintViolation<T>>): String {
        if (violations.isEmpty()) {
            return "No validation violations."
        }

        val violationMessages = violations.map { violation ->
            val propertyPath = violation.propertyPath.toString()
            val invalidValue = violation.invalidValue
            val message = violation.message

            "- Field '$propertyPath' with value '$invalidValue': $message"
        }

        return "Validation Violations:\n" + violationMessages.joinToString("\n")
    }
}
