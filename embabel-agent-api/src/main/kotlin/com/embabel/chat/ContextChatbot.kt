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

import com.embabel.agent.core.Context
import com.embabel.agent.identity.User
import com.embabel.agent.spi.ContextRepository


/*
 * A chatbot that uses Context to store messages.
 */
class ContextChatbot(
    private val contextRepository: ContextRepository,
    private val sessionFactory: SessionFactory,
) : Chatbot {

    /**
     * Create a ChatSession from a conversation.
     * Implementation can hold other state.
     */
    fun interface SessionFactory {

        fun create(
            conversation: Conversation,
            context: Context,
        ): ChatSession
    }

    override fun createSession(
        user: User?,
        systemMessage: String?,
    ): ChatSession {
        val context = contextRepository.create()
        val conversation = InMemoryConversation(id = context.id)
        context.addObject(conversation)
        contextRepository.save(context)
        return sessionFactory.create(conversation, context)
    }

    override fun findSession(conversationId: String): ChatSession? {
        return contextRepository.findById(conversationId)?.let { context ->
            val conversation = context.last(Conversation::class.java)
                ?: error("Conversation not found in context ${context.id}")
            sessionFactory.create(conversation, context)
        }
    }

}
