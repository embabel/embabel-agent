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

import com.embabel.agent.api.identity.User

/**
 * Type of conversation storage.
 */
enum class ConversationStoreType {
    /**
     * Conversations are stored in memory only.
     * Fast and simple, suitable for testing and ephemeral sessions.
     */
    IN_MEMORY,

    /**
     * Conversations are persisted to a backing store.
     * The specific store (e.g., Neo4j) is configured at factory level.
     */
    STORED
}

/**
 * Factory for creating [Conversation] instances.
 *
 * Implementations provide different storage strategies (in-memory, persistent, etc.).
 * Use [ConversationFactoryProvider] to obtain factories by type.
 */
interface ConversationFactory {

    /**
     * The storage type this factory provides.
     */
    val storeType: ConversationStoreType

    /**
     * Create a new conversation with the given ID.
     *
     * @param id unique identifier for the conversation
     * @return a new Conversation instance
     */
    fun create(id: String): Conversation

    /**
     * Create a conversation for a 1-1 chat between a user and an agent.
     *
     * Messages can be automatically attributed based on role when participants are set.
     *
     * @param id the conversation/session ID
     * @param user the human user participant
     * @param agent the AI/system user participant (optional)
     * @param title the session title (optional)
     * @return a new Conversation instance
     */
    fun createForParticipants(
        id: String,
        user: User,
        agent: User? = null,
        title: String? = null
    ): Conversation = create(id)
}
