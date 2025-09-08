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

import com.embabel.agent.identity.User

/**
 * A chatbot can conduct multiple chat sessions,
 * each identified by a contextId.
 */
interface Chatbot {

    /**
     * Create a new chat session.
     * If user is provided, the session will be associated with that user.
     * Optionally, a system message can be provided to set the context for the session.
     */
    fun createSession(
        user: User?,
        systemMessage: String? = null,
    ): ChatSession

    /**
     * Get a chat session by conversation id.
     */
    fun findSession(conversationId: String): ChatSession?
}
