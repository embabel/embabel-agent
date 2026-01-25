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
package com.embabel.agent.api.validation.guardrails

import com.embabel.agent.api.validation.ContentValidator

/**
 * Base guardrail interface for AI interaction safety and policy enforcement.
 *
 * GuardRails are specialized validators that focus on string-based validation
 * for prompts, responses, and other text content in AI interactions. They extend
 * the general ContentValidator framework to provide AI-specific safety measures.
 *
 * GuardRails validate content for:
 * - Safety and toxicity concerns
 * - Organizational policy compliance
 * - Data privacy and sensitive information
 * - Content appropriateness and quality
 * - Regulatory compliance requirements
 */
sealed interface  GuardRail : ContentValidator<String> {
    // Inherits:
    // - val name: String
    // - val description: String
    // - fun validate(input: String, blackboard: Blackboard): ValidationResult
}
