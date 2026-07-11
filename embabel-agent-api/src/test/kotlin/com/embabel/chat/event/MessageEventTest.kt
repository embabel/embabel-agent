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
package com.embabel.chat.event

import com.embabel.chat.MessageRole
import com.embabel.chat.UserMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class MessageEventTest {

    @Test
    fun `added derives status content and role from the message`() {
        val message = UserMessage("Hello")

        val event = MessageEvent.added("conv-1", message, fromUserId = "u1", toUserId = "u2")

        assertEquals("conv-1", event.conversationId)
        assertEquals(MessageStatus.ADDED, event.status)
        assertSame(message, event.message)
        assertEquals("Hello", event.content)
        assertEquals(MessageRole.USER, event.role)
        assertEquals("u1", event.fromUserId)
        assertEquals("u2", event.toUserId)
        assertNull(event.error)
    }

    @Test
    fun `persisted sets PERSISTED status`() {
        val event = MessageEvent.persisted("conv-1", UserMessage("Saved"))

        assertEquals(MessageStatus.PERSISTED, event.status)
        assertEquals("Saved", event.content)
    }

    @Test
    fun `persistenceFailed carries the error and raw content`() {
        val boom = IllegalStateException("db down")

        val event = MessageEvent.persistenceFailed(
            conversationId = "conv-1",
            content = "Lost",
            role = MessageRole.ASSISTANT,
            error = boom,
        )

        assertEquals(MessageStatus.PERSISTENCE_FAILED, event.status)
        assertEquals("Lost", event.content)
        assertEquals(MessageRole.ASSISTANT, event.role)
        assertSame(boom, event.error)
        assertNull(event.message)
    }
}
