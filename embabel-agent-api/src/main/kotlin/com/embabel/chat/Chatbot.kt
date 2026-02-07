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

import com.embabel.agent.api.channel.OutputChannel
import com.embabel.agent.api.identity.User
import com.embabel.agent.core.ContextId

/**
 * A chatbot can conduct multiple chat sessions,
 * each identified by a contextId.
 */
interface Chatbot {

    /**
     * Create a new chat session.
     * If user is provided, the session will be associated with that user.
     * If contextId is provided, the session will load objects from that context
     * into the blackboard (e.g., user preferences, prior state).
     * Optionally, a system message can be provided to set the context for the session.
     * @param user the user to associate the session with, or null for anonymous
     * @param outputChannel the output channel to send messages to
     * @param contextId optional context ID to load prior state from
     * @param systemMessage optional system message to set the context for the session
     */
    fun createSession(
        user: User?,
        outputChannel: OutputChannel,
        contextId: ContextId? = null,
        systemMessage: String? = null,
    ): ChatSession

    /**
     * Java-friendly overload that accepts contextId as a String.
     */
    fun createSession(
        user: User?,
        outputChannel: OutputChannel,
        contextId: String?,
        systemMessage: String? = null,
    ): ChatSession = createSession(
        user = user,
        outputChannel = outputChannel,
        contextId = contextId?.let { ContextId(it) },
        systemMessage = systemMessage,
    )

    /**
     * Get a chat session by conversation id.
     */
    fun findSession(conversationId: String): ChatSession?
}
