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
package com.embabel.agent.spi.validation

import jakarta.validation.ConstraintViolation
import jakarta.validation.Validator

/**
 * Generate validation prompts for JSR-380 annotated types
 */
interface ValidationPromptGenerator {

    /**
     * Generate a string describing validation requirements for an LLM prompt
     * This inspects the bean metadata to describe constraints ahead of time
     */
    fun generateRequirementsPrompt(
        validator: Validator,
        outputClass: Class<*>,
    ): String

    /**
     * Generate a string based on actual constraint violations
     * This describes what went wrong after validation
     */
    fun <T> generateViolationsReport(violations: Set<ConstraintViolation<T>>): String

}
