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
package com.embabel.chat

import com.embabel.agent.api.annotation.AwaitableResponseException
import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.core.hitl.Awaitable
import com.embabel.agent.core.hitl.ConfirmationRequest
import com.embabel.agent.domain.io.AssistantContent
import com.embabel.agent.domain.io.UserContent
import com.embabel.agent.domain.library.HasContent
import com.embabel.common.core.types.Timestamped
import com.embabel.common.util.trim
import java.time.Instant

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
 * Message class for agent system - now supports multimodal content
 * @param role Role of the message sender
 * @param parts List of content parts (text, images, etc.)
 * @param name of the sender, if available
 * @param timestamp when the message was created
 */
sealed class Message(
    val role: Role,
    val parts: List<ContentPart>,
    val name: String? = null,
    override val timestamp: Instant = Instant.now(),
) : HasContent, Timestamped {

    init {
        require(parts.isNotEmpty()) { "Message must contain at least one content part" }
    }

    /**
     * Maintains backward compatibility with HasContent interface.
     * Returns concatenated text from all TextParts.
     */
    override val content: String
        get() = textContent

    /**
     * Returns the text content of the message by concatenating all TextParts.
     */
    val textContent: String
        get() = parts.filterIsInstance<TextPart>().joinToString("") { it.text }

    /**
     * Returns all image parts in this message.
     */
    val imageParts: List<ImagePart>
        get() = parts.filterIsInstance<ImagePart>()

    /**
     * Returns true if this message contains any non-text content.
     */
    val isMultimodal: Boolean
        get() = parts.any { it !is TextPart }

    val sender: String get() = name ?: role.name.lowercase().replaceFirstChar { it.uppercase() }
}

/**
 * Message sent by the user - supports multimodal content
 */
class UserMessage : Message, UserContent {

    /**
     * Primary constructor for multimodal messages
     */
    constructor(
        parts: List<ContentPart>,
        name: String? = null,
        timestamp: Instant = Instant.now(),
    ) : super(role = Role.USER, parts = parts, name = name, timestamp = timestamp)

    /**
     * Convenience constructor for text-only messages (backward compatibility)
     */
    @JvmOverloads
    constructor(
        content: String,
        name: String? = null,
        timestamp: Instant = Instant.now(),
    ) : this(parts = listOf(TextPart(content)), name = name, timestamp = timestamp)

    override fun toString(): String {
        return "UserMessage(from='${sender}', content='${trim(content, 80, 10)}')"
    }
}

/**
 * Message sent by the assistant - currently text-only
 * @param content Content of the message
 * @param name Name of the assistant, if available
 * @param awaitable Awaitable associated with this message, if any
 * Enables forms to be put in front of users
 */
open class AssistantMessage @JvmOverloads constructor(
    content: String,
    name: String? = null,
    val awaitable: Awaitable<*, *>? = null,
    override val timestamp: Instant = Instant.now(),
) : Message(
    role = Role.ASSISTANT,
    parts = listOf(TextPart(content)),
    name = name,
    timestamp = timestamp
), AssistantContent {

    override fun toString(): String {
        return "AssistantMessage(from='${sender}', content='${trim(content, 80, 10)}')"
    }

    companion object {

        @JvmStatic
        @JvmOverloads
        fun <P : Any> confirmationRequest(
            confirmationRequest: ConfirmationRequest<P>,
            conversation: Conversation,
            context: ActionContext,
            name: String? = null,
        ): P {
            val assistantMessage = AssistantMessage(
                content = confirmationRequest.message,
                name = name,
                awaitable = confirmationRequest,
            )
            conversation.addMessage(assistantMessage)
            context.sendMessage(assistantMessage)
            throw AwaitableResponseException(
                awaitable = confirmationRequest,
            )
        }

//        @JvmStatic
//        @JvmOverloads
//        fun ofFormSubmission(
//            form: FormBindingRequest<*>,
//            name: String? = null,
//        ): AssistantMessage {
//            return AssistantMessage(
//                content = form.payload.title,
//                name = name,
//                awaitable = form,
//            )
//        }
    }
}

/**
 * System message - text-only
 */
class SystemMessage @JvmOverloads constructor(
    content: String,
    override val timestamp: Instant = Instant.now(),
) : Message(role = Role.SYSTEM, parts = listOf(TextPart(content)), name = null, timestamp = timestamp) {

    override fun toString(): String {
        return "SystemMessage(content='${trim(content, 80, 10)}')"
    }

}
