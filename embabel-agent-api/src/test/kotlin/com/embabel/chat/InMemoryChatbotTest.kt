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
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class InMemoryChatbotTest {

    private fun createTestChatbot(
        maxSessions: Int = 1000,
        evictionBatchSize: Int = maxOf(1, maxSessions / 10),
    ): TestInMemoryChatbot {
        return TestInMemoryChatbot(maxSessions, evictionBatchSize)
    }

    @Nested
    inner class ConstructorTests {

        @Test
        fun `test default constructor parameters`() {
            val chatbot = createTestChatbot()
            assertEquals(1000, chatbot.getMaxSessions(), "Default maxSessions should be 1000")
        }

        @Test
        fun `test custom constructor parameters`() {
            val chatbot = createTestChatbot(maxSessions = 100, evictionBatchSize = 20)
            assertEquals(100, chatbot.getMaxSessions(), "Custom maxSessions should be set correctly")
        }

        @Test
        fun `test constructor validation - negative maxSessions`() {
            assertThrows<IllegalArgumentException> {
                createTestChatbot(maxSessions = -1)
            }
        }

        @Test
        fun `test constructor validation - zero maxSessions`() {
            assertThrows<IllegalArgumentException> {
                createTestChatbot(maxSessions = 0)
            }
        }

        @Test
        fun `test constructor validation - negative evictionBatchSize`() {
            assertThrows<IllegalArgumentException> {
                createTestChatbot(maxSessions = 100, evictionBatchSize = -1)
            }
        }

        @Test
        fun `test constructor validation - zero evictionBatchSize`() {
            assertThrows<IllegalArgumentException> {
                createTestChatbot(maxSessions = 100, evictionBatchSize = 0)
            }
        }

        @Test
        fun `test constructor validation - evictionBatchSize exceeds maxSessions`() {
            assertThrows<IllegalArgumentException> {
                createTestChatbot(maxSessions = 10, evictionBatchSize = 20)
            }
        }
    }

    @Nested
    inner class SessionManagementTests {

        @Test
        fun `test createSession creates and registers session`() {
            val chatbot = createTestChatbot()

            val session = chatbot.createSession(user = null, systemMessage = "Test system message")

            assertNotNull(session, "Session should not be null")
            assertEquals(1, chatbot.getActiveSessionCount(), "Should have one active session")

            val foundSession = chatbot.findSession(session.conversation.id)
            assertEquals(session, foundSession, "Should be able to find the created session")
        }

        @Test
        fun `test createSession with null system message`() {
            val chatbot = createTestChatbot()

            val session = chatbot.createSession(null)

            assertNotNull(session, "Session should not be null")
            assertEquals(1, chatbot.getActiveSessionCount(), "Should have one active session")
        }

        @Test
        fun `test findSession returns null for non-existent session`() {
            val chatbot = createTestChatbot()

            val foundSession = chatbot.findSession("non-existent-id")

            assertNull(foundSession, "Should return null for non-existent session")
        }

        @Test
        fun `test findSession updates last accessed time`() {
            val chatbot = createTestChatbot()
            val session = chatbot.createSession(user = null)

            Thread.sleep(10) // Ensure time difference
            val foundSession1 = chatbot.findSession(session.conversation.id)
            Thread.sleep(10)
            val foundSession2 = chatbot.findSession(session.conversation.id)

            assertEquals(session, foundSession1, "Should find the same session")
            assertEquals(session, foundSession2, "Should find the same session")
        }

        @Test
        fun `test removeSession removes existing session`() {
            val chatbot = createTestChatbot()
            val session = chatbot.createSession(user = null)

            val removed = chatbot.removeSession(session.conversation.id)

            assertTrue(removed, "Should return true when session is removed")
            assertEquals(0, chatbot.getActiveSessionCount(), "Should have no active sessions")
            assertNull(chatbot.findSession(session.conversation.id), "Should not find removed session")
        }

        @Test
        fun `test removeSession returns false for non-existent session`() {
            val chatbot = createTestChatbot()

            val removed = chatbot.removeSession("non-existent-id")

            assertFalse(removed, "Should return false when session doesn't exist")
        }

        @Test
        fun `test clearAllSessions removes all sessions`() {
            val chatbot = createTestChatbot()
            repeat(5) { chatbot.createSession(user = null) }
            assertEquals(5, chatbot.getActiveSessionCount(), "Should have 5 sessions")

            chatbot.clearAllSessions()

            assertEquals(0, chatbot.getActiveSessionCount(), "Should have no active sessions")
        }

        @Test
        fun `test getActiveConversationIds returns correct IDs`() {
            val chatbot = createTestChatbot()
            val sessions = mutableListOf<ChatSession>()
            repeat(3) { sessions.add(chatbot.createSession(user = null)) }

            val conversationIds = chatbot.getActiveConversationIds()

            assertEquals(3, conversationIds.size, "Should have 3 conversation IDs")
            sessions.forEach { session ->
                assertTrue(
                    conversationIds.contains(session.conversation.id),
                    "Should contain session conversation ID"
                )
            }
        }
    }

    @Nested
    inner class EvictionTests {

        @Test
        fun `test session eviction when limit is reached`() {
            val chatbot = createTestChatbot(maxSessions = 5, evictionBatchSize = 2)
            val sessions = mutableListOf<ChatSession>()

            // Create sessions up to the limit
            repeat(5) { sessions.add(chatbot.createSession(user = null)) }
            assertEquals(5, chatbot.getActiveSessionCount(), "Should have 5 sessions at limit")

            // Access some sessions to update their last accessed time
            Thread.sleep(10)
            chatbot.findSession(sessions[2].conversation.id)
            chatbot.findSession(sessions[3].conversation.id)
            chatbot.findSession(sessions[4].conversation.id)
            Thread.sleep(10)

            // Create a new session, which should trigger eviction
            val newSession = chatbot.createSession(user = null)

            assertEquals(4, chatbot.getActiveSessionCount(), "Should have 4 sessions after eviction")
            assertTrue(
                chatbot.getActiveConversationIds().contains(newSession.conversation.id),
                "New session should be present"
            )
        }

        @Test
        fun `test eviction removes oldest sessions`() {
            val chatbot = createTestChatbot(maxSessions = 3, evictionBatchSize = 1)
            val sessions = mutableListOf<ChatSession>()

            // Create sessions
            repeat(3) {
                sessions.add(chatbot.createSession(user = null))
                Thread.sleep(10) // Ensure different timestamps
            }

            // Access the last two sessions to make the first one the oldest
            chatbot.findSession(sessions[1].conversation.id)
            chatbot.findSession(sessions[2].conversation.id)
            Thread.sleep(10)

            // Create a new session, which should evict the oldest (sessions[0])
            val newSession = chatbot.createSession(user = null)

            assertEquals(3, chatbot.getActiveSessionCount(), "Should still have 3 sessions")
            assertNull(
                chatbot.findSession(sessions[0].conversation.id),
                "Oldest session should be evicted"
            )
            assertNotNull(
                chatbot.findSession(sessions[1].conversation.id),
                "Second session should still exist"
            )
            assertNotNull(
                chatbot.findSession(sessions[2].conversation.id),
                "Third session should still exist"
            )
            assertNotNull(
                chatbot.findSession(newSession.conversation.id),
                "New session should exist"
            )
        }

        @Test
        fun `test custom eviction batch size`() {
            val chatbot = createTestChatbot(maxSessions = 10, evictionBatchSize = 3)

            // Fill up to the limit
            repeat(10) { chatbot.createSession(user = null) }
            assertEquals(10, chatbot.getActiveSessionCount(), "Should have 10 sessions")

            // Create a new session, which should evict 3 sessions
            chatbot.createSession(user = null)

            assertEquals(8, chatbot.getActiveSessionCount(), "Should have 8 sessions after evicting 3")
        }
    }

    @Nested
    inner class ConcurrencyTests {

        @Test
        fun `test concurrent session creation`() {
            val chatbot = createTestChatbot(maxSessions = 100)
            val executor = Executors.newFixedThreadPool(10)
            val latch = CountDownLatch(50)
            val createdSessions = mutableSetOf<String>()
            val exceptions = mutableListOf<Exception>()

            repeat(50) {
                executor.submit {
                    try {
                        val session = chatbot.createSession(user = null)
                        synchronized(createdSessions) {
                            createdSessions.add(session.conversation.id)
                        }
                    } catch (e: Exception) {
                        synchronized(exceptions) {
                            exceptions.add(e)
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS), "All tasks should complete")
            executor.shutdown()

            assertTrue(exceptions.isEmpty(), "Should have no exceptions: $exceptions")
            assertEquals(50, createdSessions.size, "Should have created 50 unique sessions")
            assertEquals(50, chatbot.getActiveSessionCount(), "Should have 50 active sessions")
        }

        @Test
        fun `test concurrent findSession calls`() {
            val chatbot = createTestChatbot()
            val session = chatbot.createSession(user = null)
            val conversationId = session.conversation.id

            val executor = Executors.newFixedThreadPool(10)
            val latch = CountDownLatch(100)
            val foundSessions = AtomicInteger(0)
            val exceptions = mutableListOf<Exception>()

            repeat(100) {
                executor.submit {
                    try {
                        val found = chatbot.findSession(conversationId)
                        if (found != null) {
                            foundSessions.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        synchronized(exceptions) {
                            exceptions.add(e)
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS), "All tasks should complete")
            executor.shutdown()

            assertTrue(exceptions.isEmpty(), "Should have no exceptions: $exceptions")
            assertEquals(100, foundSessions.get(), "All calls should find the session")
        }

        @Test
        fun `test concurrent session creation and removal`() {
            val chatbot = createTestChatbot(maxSessions = 20)
            val executor = Executors.newFixedThreadPool(20)
            val latch = CountDownLatch(100)
            val exceptions = mutableListOf<Exception>()

            // Mix of creation and removal operations
            repeat(100) { i ->
                executor.submit {
                    try {
                        if (i % 3 == 0) {
                            // Create session
                            chatbot.createSession(user = null)
                        } else if (i % 3 == 1) {
                            // Try to remove random session
                            val activeIds = chatbot.getActiveConversationIds()
                            if (activeIds.isNotEmpty()) {
                                val randomId = activeIds.random()
                                chatbot.removeSession(randomId)
                            }
                        } else {
                            // Try to find random session
                            val activeIds = chatbot.getActiveConversationIds()
                            if (activeIds.isNotEmpty()) {
                                val randomId = activeIds.random()
                                chatbot.findSession(randomId)
                            }
                        }
                    } catch (e: Exception) {
                        synchronized(exceptions) {
                            exceptions.add(e)
                        }
                    } finally {
                        latch.countDown()
                    }
                }
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS), "All tasks should complete")
            executor.shutdown()

            assertTrue(exceptions.isEmpty(), "Should have no exceptions: $exceptions")
            assertTrue(
                chatbot.getActiveSessionCount() <= 20,
                "Should not exceed max sessions: ${chatbot.getActiveSessionCount()}"
            )
        }
    }

    @Nested
    inner class EdgeCaseTests {

        @Test
        fun `test duplicate conversation ID handling`() {
            val chatbot = TestInMemoryChatbotWithDuplicateId()

            // First creation should succeed
            val session1 = chatbot.createSession(user = null)
            assertEquals(1, chatbot.getActiveSessionCount(), "Should have one session")

            // Second creation with same ID should fail
            assertThrows<IllegalArgumentException> {
                chatbot.createSession(user = null)
            }
        }

        @Test
        fun `test session with very large maxSessions`() {
            val chatbot = createTestChatbot(maxSessions = Int.MAX_VALUE, evictionBatchSize = 1000)

            repeat(1000) { chatbot.createSession(user = null) }

            assertEquals(1000, chatbot.getActiveSessionCount(), "Should handle large number of sessions")
        }

        @Test
        fun `test session with minimal configuration`() {
            val chatbot = createTestChatbot(maxSessions = 1, evictionBatchSize = 1)

            val session1 = chatbot.createSession(user = null)
            assertEquals(1, chatbot.getActiveSessionCount(), "Should have one session")

            val session2 = chatbot.createSession(user = null)
            assertEquals(1, chatbot.getActiveSessionCount(), "Should still have one session")

            assertNull(chatbot.findSession(session1.conversation.id), "First session should be evicted")
            assertNotNull(chatbot.findSession(session2.conversation.id), "Second session should exist")
        }
    }

    // Test implementation of InMemoryChatbot
    private class TestInMemoryChatbot(
        maxSessions: Int = 1000,
        evictionBatchSize: Int = maxOf(1, maxSessions / 10),
    ) : InMemoryChatbot(maxSessions, evictionBatchSize) {

        override fun doCreateSession(
            user: User?,
            systemMessage: String?,
        ): ChatSession {
            val mockConversation = mockk<Conversation>()
            every { mockConversation.id } returns UUID.randomUUID().toString()

            val mockSession = mockk<ChatSession>()
            every { mockSession.conversation } returns mockConversation

            return mockSession
        }
    }

    // Test implementation that always returns the same conversation ID to test duplicate handling
    private class TestInMemoryChatbotWithDuplicateId : InMemoryChatbot(10, 1) {
        private val fixedId = UUID.randomUUID().toString()

        override fun doCreateSession(
            user: User?,
            systemMessage: String?,
        ): ChatSession {
            val mockConversation = mockk<Conversation>()
            every { mockConversation.id } returns fixedId

            val mockSession = mockk<ChatSession>()
            every { mockSession.conversation } returns mockConversation

            return mockSession
        }
    }
}
