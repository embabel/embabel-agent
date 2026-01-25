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


import com.embabel.agent.core.Blackboard
import com.embabel.chat.AssistantMessage
import com.embabel.common.core.thinking.ThinkingResponse
import com.embabel.common.core.validation.ValidationResult

/**
 * Validates assistant responses after LLM execution.
 *
 * AssistantMessageGuardRails provide safety and quality checks on content that
 * AI systems produce, ensuring that generated responses meet organizational
 * standards for appropriateness, accuracy, compliance, and quality.
 *
 * This interface handles validation of both standard responses and thinking-enhanced
 * responses that include internal reasoning content.
 */
interface AssistantMessageGuardRail : GuardRail {

    // Base string validation inherited from GuardRail:
    // fun validate(input: String, blackboard: Blackboard): ValidationResult

    /**
     * Validate a thinking-enhanced response containing both result and reasoning.
     *
     * This method provides access to the complete thinking response, allowing
     * validators to examine both the final result and the internal thinking blocks.
     * Implementations can validate the relationship between thinking and result,
     * check for inappropriate reasoning, or ensure thinking quality standards.
     *
     * @param response the thinking response containing result and thinking blocks
     * @param blackboard the blackboard context
     * @return validation result indicating success or failure
     */
    fun validate(response: ThinkingResponse<*>, blackboard: Blackboard): ValidationResult

    /**
     * Validate a standard assistant message response.
     *
     * This method handles validation of typical chat-style responses from
     * the assistant, checking content appropriateness and compliance.
     *
     * Default implementation validates the message content, but implementations
     * can override to examine message metadata or perform more sophisticated
     * conversation-aware validation.
     *
     * @param message the assistant message to validate
     * @param blackboard the blackboard context
     * @return validation result indicating success or failure
     */
    fun validate(message: AssistantMessage, blackboard: Blackboard): ValidationResult {
        // Default implementation validates the message content
        // Implementations can override for more sophisticated message analysis
        return validate(message.content, blackboard)
    }
}
