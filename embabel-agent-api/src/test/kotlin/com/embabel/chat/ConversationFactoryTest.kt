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

import com.embabel.chat.support.InMemoryConversation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ConversationFactoryTest {

    @Test
    fun `load default implementation returns null`() {
        val factory = object : ConversationFactory {
            override val storeType = ConversationStoreType.IN_MEMORY
            override fun create(id: String) = InMemoryConversation(id = id)
        }

        val result = factory.load("any-id")

        assertNull(result)
    }

    @Test
    fun `createForParticipants default implementation delegates to create`() {
        var createCalled = false
        val factory = object : ConversationFactory {
            override val storeType = ConversationStoreType.IN_MEMORY
            override fun create(id: String): Conversation {
                createCalled = true
                return InMemoryConversation(id = id)
            }
        }

        val result = factory.createForParticipants(
            id = "test-id",
            user = object : com.embabel.agent.api.identity.User {
                override val id = "user-1"
                override val name = "Test User"
            }
        )

        assertTrue(createCalled)
        assertEquals("test-id", result.id)
    }
}
