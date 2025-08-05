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
package com.embabel.chat

import com.embabel.agent.api.common.autonomy.AgentProcessExecution
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.MobyNameGenerator
import com.embabel.common.core.StableIdentified
import com.embabel.common.core.types.Timestamped
import java.time.Instant

/**
 * Conversation shim for agent system
 */
interface Conversation : StableIdentified {

    val messages: List<Message>

    fun withMessage(message: Message): Conversation

    fun promptContributor(
        conversationFormatter: ConversationFormatter = WindowingConversationFormatter(),
    ) = PromptContributor.dynamic({ "Conversation so far:\n" + conversationFormatter.format(this) })


}

data class InMemoryConversation(
    override val id: String = MobyNameGenerator.generateName(),
    override val messages: List<Message> = emptyList(),
    private val persistent: Boolean = false,
) : Conversation {

    override fun withMessage(message: Message): Conversation {
        return copy(
            messages = messages + message,
        )
    }

    override fun persistent(): Boolean = persistent
}

/**
 * Role of the message sender.
 * For visible messages, not user messages.
 */
enum class Role {
    USER,
    ASSISTANT,
}

/**
 * Message class for agent system
 * @param role Role of the message sender. AI system specific
 * @param content Content of the message
 * @param name of the sender, if available
 */
sealed class Message(
    val role: Role,
    val content: String,
    val name: String? = null,
    override val timestamp: Instant = Instant.now(),
) : Timestamped

/**
 * Message sent by the user.
 * @param content Content of the message
 * @param name Name of the user, if available
 */
class UserMessage(
    content: String,
    name: String? = null,
    override val timestamp: Instant = Instant.now(),
) : Message(Role.USER, content, name, timestamp)

/**
 * Message sent by the assistant.
 * @param content Content of the message
 * @param name Name of the assistant, if available
 */
open class AssistantMessage(
    content: String,
    name: String? = null,
    override val timestamp: Instant = Instant.now(),
) : Message(Role.ASSISTANT, content, name, timestamp)

/**
 * Assistant message resulting from an agentic execution
 */
class AgenticResultAssistantMessage(
    val agentProcessExecution: AgentProcessExecution,
    content: String,
    name: String? = null,
) : AssistantMessage(content = content, name = name)
