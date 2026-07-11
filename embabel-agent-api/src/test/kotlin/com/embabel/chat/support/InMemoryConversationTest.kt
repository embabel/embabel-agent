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
package com.embabel.chat.support

import com.embabel.chat.UserMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class InMemoryConversationTest {

    @Test
    fun `last returns a non-persistent conversation with the trailing messages`() {
        val conversation = InMemoryConversation(id = "c")
        repeat(5) { conversation.addMessage(UserMessage("m$it")) }

        val tail = conversation.last(2)

        assertEquals(listOf("m3", "m4"), tail.messages.map { it.content })
        assertFalse(tail.persistent())
    }

    @Test
    fun `concurrent addMessage from many threads keeps every message`() {
        val conversation = InMemoryConversation(id = "concurrent")
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        repeat(threads) {
            pool.submit {
                start.await()
                repeat(perThread) { conversation.addMessage(UserMessage("m")) }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals(threads * perThread, conversation.messages.size)
    }

    @Test
    fun `reading messages while another thread writes does not throw`() {
        val conversation = InMemoryConversation(id = "read-write")
        val pool = Executors.newSingleThreadExecutor()
        val writer = pool.submit {
            repeat(5000) { conversation.addMessage(UserMessage("m")) }
        }

        // Forces iteration of the backing list on every call; would throw
        // ConcurrentModificationException against a non-thread-safe list.
        while (!writer.isDone) {
            assertTrue(conversation.messages.size >= 0)
        }

        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertEquals(5000, conversation.messages.size)
    }
}
