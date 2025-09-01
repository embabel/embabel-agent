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
import com.embabel.agent.domain.library.HasContent
import com.embabel.common.ai.prompt.PromptContributor
import com.embabel.common.core.StableIdentified
import com.embabel.common.core.types.HasInfoString
import com.embabel.common.core.types.Timestamped
import java.time.Instant

/**
 * Conversation shim for agent system
 */
interface Conversation : StableIdentified, HasInfoString {

    val messages: List<Message>

    /**
     * Non-null if the conversation has messages and the last message is from the user.
     */
    fun lastMessageMustBeFromUser(): UserMessage? = messages.lastOrNull() as? UserMessage

    fun withMessage(message: Message): Conversation

    fun promptContributor(
        conversationFormatter: ConversationFormatter = WindowingConversationFormatter(),
    ) = PromptContributor.dynamic({ "Conversation so far:\n" + conversationFormatter.format(this) })

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return promptContributor().contribution()
    }
}

/**
 * Role of the message sender.
 * For visible messages, not user messages.
 */
enum class Role {
    USER,
    ASSISTANT,
    SYSTEM,
}

/**
 * Message class for agent system
 * @param role Role of the message sender. AI system specific
 * @param content Content of the message
 * @param name of the sender, if available
 */
sealed class Message(
    val role: Role,
    override val content: String,
    val name: String? = null,
    override val timestamp: Instant = Instant.now(),
) : HasContent, Timestamped {

    val sender: String get() = name ?: role.name.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * Message sent by the user.
 * @param content Content of the message
 * @param name Name of the user, if available
 */
class UserMessage @JvmOverloads constructor(
    content: String,
    name: String? = null,
    override val timestamp: Instant = Instant.now(),
) : Message(role = Role.USER, content = content, name = name, timestamp = timestamp)

/**
 * Message sent by the assistant.
 * @param content Content of the message
 * @param name Name of the assistant, if available
 */
open class AssistantMessage @JvmOverloads constructor(
    content: String,
    name: String? = null,
    override val timestamp: Instant = Instant.now(),
) : Message(role = Role.ASSISTANT, content = content, name = name, timestamp = timestamp)

class SystemMessage @JvmOverloads constructor(
    content: String,
    override val timestamp: Instant = Instant.now(),
) : Message(role = Role.SYSTEM, content = content, name = null, timestamp = timestamp)

/**
 * Assistant message resulting from an agentic execution
 */
class AgenticResultAssistantMessage(
    val agentProcessExecution: AgentProcessExecution,
    content: String,
    name: String? = null,
) : AssistantMessage(content = content, name = name)
