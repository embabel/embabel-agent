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

import com.embabel.agent.api.common.MultimodalContent
import com.embabel.agent.core.Blackboard
import com.embabel.chat.UserMessage
import com.embabel.common.core.validation.ValidationResult

/**
 * Validates user inputs before LLM execution.
 *
 * UserInputGuardRails provide safety checks on content that users provide to
 * AI systems, ensuring that potentially harmful, inappropriate, or policy-violating
 * content is detected and handled before being processed by the LLM.
 *
 * This interface provides overloads for different input types while maintaining
 * the base string validation from the GuardRail interface.
 */
interface UserInputGuardRail : GuardRail {

    // Base string validation inherited from GuardRail:
    // fun validate(input: String, blackboard: Blackboard): ValidationResult

    /**
     * Combines multiple user messages into a single string for validation.
     *
     * Override this method to customize how messages are combined before validation.
     * For example, implementations might want to:
     * - Add separators or context markers between messages
     * - Filter out certain message types
     * - Apply message-specific preprocessing
     *
     * @param userMessages the list of user messages to combine
     * @return the combined text representation of the messages
     */
    fun combineMessages(userMessages: List<UserMessage>): String {
        return userMessages.joinToString(separator = "\n") { message ->
            message.content
        }
    }

    /**
     * Validate a list of user messages from a conversation.
     *
     * This method allows validation of multi-turn user inputs and can examine
     * the context and flow of user messages in the conversation.
     *
     * Default implementation uses [combineMessages] to extract and combine text content
     * from all user messages, then validates the combined text. Implementations can
     * override [combineMessages] for custom message combination, or override this
     * method entirely for more sophisticated conversation-aware validation.
     *
     * @param userMessages the list of user messages to validate
     * @param blackboard the blackboard context
     * @return validation result indicating success or failure
     */
    fun validate(userMessages: List<UserMessage>, blackboard: Blackboard): ValidationResult {
        val combinedText = combineMessages(userMessages)
        return validate(combinedText, blackboard)
    }

    /**
     * Validate multimodal content containing text and potentially images.
     *
     * This method handles validation of content that may include both textual
     * and visual components. The default implementation validates only the text
     * portion, but implementations can override to provide image content analysis.
     *
     * @param content the multimodal content to validate
     * @param blackboard the blackboard context
     * @return validation result indicating success or failure
     */
    fun validate(content: MultimodalContent, blackboard: Blackboard): ValidationResult {
        // Default implementation validates only text content
        // Implementations can override to handle image analysis
        return validate(content.text, blackboard)
    }
}
