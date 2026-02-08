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

/**
 * A chatbot can conduct multiple chat sessions,
 * each identified by a conversationId.
 */
interface Chatbot {

    /**
     * Create a new chat session, or restore an existing one.
     *
     * If [contextId] is provided, objects from that context are loaded into the blackboard
     * (e.g., user preferences, prior state).
     *
     * If [conversationId] is provided and a conversation exists in storage,
     * the session will be restored with its message history. Otherwise,
     * a new conversation is created with that ID.
     *
     * @param user the user to associate the session with, or null for anonymous
     * @param outputChannel the output channel to send messages to
     * @param contextId optional context ID to load prior state from
     * @param conversationId optional ID to restore an existing conversation, or create with specific ID
     */
    fun createSession(
        user: User?,
        outputChannel: OutputChannel,
        contextId: String? = null,
        conversationId: String? = null,
    ): ChatSession

    /**
     * Get a chat session by conversation id.
     */
    fun findSession(conversationId: String): ChatSession?
}
