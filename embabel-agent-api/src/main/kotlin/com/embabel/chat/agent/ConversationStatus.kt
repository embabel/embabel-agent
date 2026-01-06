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
package com.embabel.chat.agent

import com.embabel.chat.AssistantMessage
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import com.fasterxml.jackson.annotation.JsonTypeInfo

/**
 * Convenient supertype for chatbot agent returns.
 * User code doesn't to use these types, but they are
 * a good pattern for typical conversation flow.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.SIMPLE_NAME,
    include = JsonTypeInfo.As.PROPERTY,
    property = "type"
)
sealed interface ConversationStatus

data class ConversationContinues(
    val assistantMessage: AssistantMessage,
) : ConversationStatus {

    companion object {

        @JvmStatic
        fun with(assistantMessage: AssistantMessage): ConversationContinues =
            ConversationContinues(assistantMessage)
    }
}

data class ConversationOver(
    @get:JsonPropertyDescription("Reason for conversation termination, e.g. 'user requested end of conversation', or 'conversation unsafe'")
    val reason: String,
) : ConversationStatus {

    companion object {

        @JvmStatic
        fun because(reason: String): ConversationOver =
            ConversationOver(reason)
    }
}
