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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Abstract implementation of [Chatbot] that maintains sessions in memory.
 * This implementation is thread-safe and supports configurable maximum number of sessions.
 * When the maximum number of sessions is reached, the oldest sessions are evicted to make room for new ones.
 *
 * @param maxSessions The maximum number of concurrent chat sessions to maintain. Defaults to 1000.
 * @param evictionBatchSize The number of sessions to evict when the limit is reached. Defaults to 10% of maxSessions.
 */
abstract class InMemoryChatbot(
    private val maxSessions: Int = 1000,
    private val evictionBatchSize: Int = maxOf(1, maxSessions / 10),
) : Chatbot {

    private val sessions = ConcurrentHashMap<String, SessionEntry>()
    private val lock = ReentrantReadWriteLock()

    init {
        require(maxSessions > 0) { "maxSessions must be positive, got: $maxSessions" }
        require(evictionBatchSize > 0) { "evictionBatchSize must be positive, got: $evictionBatchSize" }
        require(evictionBatchSize <= maxSessions) {
            "evictionBatchSize ($evictionBatchSize) must not exceed maxSessions ($maxSessions)"
        }
    }

    /**
     * Creates a new chat session and automatically registers it with the chatbot.
     * Subclasses must implement [doCreateSession] to provide their specific session implementation.
     *
     * @param systemMessage Optional system message to initialize the session with
     * @return A new [ChatSession] instance
     */
    final override fun createSession(
        user: User?,
        systemMessage: String?,
    ): ChatSession {
        return lock.write {
            // Check if we need to evict sessions before adding the new one
            if (sessions.size >= maxSessions) {
                evictOldestSessions()
            }

            val session = doCreateSession(user = user, systemMessage = systemMessage)
            val conversationId = session.conversation.id

            if (sessions.containsKey(conversationId)) {
                throw IllegalArgumentException("Session with conversationId '$conversationId' already exists")
            }

            sessions[conversationId] = SessionEntry(session, System.currentTimeMillis())
            session
        }
    }

    /**
     * Creates a new chat session. Subclasses must implement this method to provide
     * their specific session implementation.
     *
     * @param systemMessage Optional system message to initialize the session with
     * @return A new [ChatSession] instance
     */
    protected abstract fun doCreateSession(
        user: User?,
        systemMessage: String?,
    ): ChatSession

    /**
     * Finds an existing chat session by its conversation ID.
     *
     * @param conversationId The ID of the conversation to find
     * @return The [ChatSession] if found, or null if not found
     */
    final override fun findSession(conversationId: String): ChatSession? {
        return lock.read {
            sessions[conversationId]?.let { entry ->
                entry.lastAccessed = System.currentTimeMillis()
                entry.session
            }
        }
    }


    /**
     * Removes a chat session from the chatbot.
     *
     * @param conversationId The ID of the conversation to remove
     * @return true if the session was removed, false if it didn't exist
     */
    fun removeSession(conversationId: String): Boolean {
        return lock.write {
            sessions.remove(conversationId) != null
        }
    }

    /**
     * Gets the current number of active sessions.
     *
     * @return The number of active sessions
     */
    fun getActiveSessionCount(): Int {
        return lock.read { sessions.size }
    }

    /**
     * Gets the maximum number of sessions that can be maintained.
     *
     * @return The maximum session limit
     */
    fun getMaxSessions(): Int = maxSessions

    /**
     * Clears all sessions from memory.
     */
    fun clearAllSessions() {
        lock.write {
            sessions.clear()
        }
    }

    /**
     * Gets all active conversation IDs.
     *
     * @return A set of all active conversation IDs
     */
    fun getActiveConversationIds(): Set<String> {
        return lock.read { sessions.keys.toSet() }
    }

    /**
     * Evicts the oldest sessions to make room for new ones.
     * This method is called automatically when the session limit is reached.
     */
    private fun evictOldestSessions() {
        // Sort sessions by last accessed time and remove the oldest ones
        val sortedEntries = sessions.entries.sortedBy { it.value.lastAccessed }
        val toEvict = sortedEntries.take(evictionBatchSize)

        toEvict.forEach { (conversationId, _) ->
            sessions.remove(conversationId)
        }
    }

    /**
     * Internal data class to track session metadata.
     */
    private data class SessionEntry(
        val session: ChatSession,
        var lastAccessed: Long,
    )
}
