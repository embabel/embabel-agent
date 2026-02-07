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
import com.embabel.chat.support.InMemoryConversation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [MapConversationFactoryProvider] and related types.
 */
class ConversationFactoryProviderTest {

    @Nested
    inner class MapConversationFactoryProviderTests {

        @Test
        fun `getFactory returns registered factory for type`() {
            val inMemoryFactory = TestConversationFactory(ConversationStoreType.IN_MEMORY)
            val provider = MapConversationFactoryProvider(inMemoryFactory)

            val result = provider.getFactory(ConversationStoreType.IN_MEMORY)

            assertThat(result).isSameAs(inMemoryFactory)
        }

        @Test
        fun `getFactory throws for unregistered type`() {
            val inMemoryFactory = TestConversationFactory(ConversationStoreType.IN_MEMORY)
            val provider = MapConversationFactoryProvider(inMemoryFactory)

            assertThatThrownBy { provider.getFactory(ConversationStoreType.STORED) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("No ConversationFactory registered for type STORED")
                .hasMessageContaining("Available: [IN_MEMORY]")
        }

        @Test
        fun `getFactory throws with empty provider`() {
            val provider = MapConversationFactoryProvider(emptyMap())

            assertThatThrownBy { provider.getFactory(ConversationStoreType.IN_MEMORY) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("No ConversationFactory registered for type IN_MEMORY")
        }

        @Test
        fun `getFactoryOrNull returns factory when registered`() {
            val storedFactory = TestConversationFactory(ConversationStoreType.STORED)
            val provider = MapConversationFactoryProvider(storedFactory)

            val result = provider.getFactoryOrNull(ConversationStoreType.STORED)

            assertThat(result).isSameAs(storedFactory)
        }

        @Test
        fun `getFactoryOrNull returns null when not registered`() {
            val inMemoryFactory = TestConversationFactory(ConversationStoreType.IN_MEMORY)
            val provider = MapConversationFactoryProvider(inMemoryFactory)

            val result = provider.getFactoryOrNull(ConversationStoreType.STORED)

            assertThat(result).isNull()
        }

        @Test
        fun `availableTypes returns all registered types`() {
            val inMemoryFactory = TestConversationFactory(ConversationStoreType.IN_MEMORY)
            val storedFactory = TestConversationFactory(ConversationStoreType.STORED)
            val provider = MapConversationFactoryProvider(inMemoryFactory, storedFactory)

            val result = provider.availableTypes()

            assertThat(result).containsExactlyInAnyOrder(
                ConversationStoreType.IN_MEMORY,
                ConversationStoreType.STORED
            )
        }

        @Test
        fun `availableTypes returns empty set for empty provider`() {
            val provider = MapConversationFactoryProvider(emptyMap())

            val result = provider.availableTypes()

            assertThat(result).isEmpty()
        }

        @Test
        fun `constructor with list creates provider correctly`() {
            val inMemoryFactory = TestConversationFactory(ConversationStoreType.IN_MEMORY)
            val storedFactory = TestConversationFactory(ConversationStoreType.STORED)
            val provider = MapConversationFactoryProvider(listOf(inMemoryFactory, storedFactory))

            assertThat(provider.getFactory(ConversationStoreType.IN_MEMORY)).isSameAs(inMemoryFactory)
            assertThat(provider.getFactory(ConversationStoreType.STORED)).isSameAs(storedFactory)
        }

        @Test
        fun `constructor with vararg creates provider correctly`() {
            val inMemoryFactory = TestConversationFactory(ConversationStoreType.IN_MEMORY)
            val storedFactory = TestConversationFactory(ConversationStoreType.STORED)
            val provider = MapConversationFactoryProvider(inMemoryFactory, storedFactory)

            assertThat(provider.getFactory(ConversationStoreType.IN_MEMORY)).isSameAs(inMemoryFactory)
            assertThat(provider.getFactory(ConversationStoreType.STORED)).isSameAs(storedFactory)
        }

        @Test
        fun `constructor with map creates provider correctly`() {
            val inMemoryFactory = TestConversationFactory(ConversationStoreType.IN_MEMORY)
            val provider = MapConversationFactoryProvider(
                mapOf(ConversationStoreType.IN_MEMORY to inMemoryFactory)
            )

            assertThat(provider.getFactory(ConversationStoreType.IN_MEMORY)).isSameAs(inMemoryFactory)
        }

        @Test
        fun `duplicate store type in vararg uses last factory`() {
            val factory1 = TestConversationFactory(ConversationStoreType.IN_MEMORY)
            val factory2 = TestConversationFactory(ConversationStoreType.IN_MEMORY)
            val provider = MapConversationFactoryProvider(factory1, factory2)

            // associateBy uses last value for duplicate keys
            assertThat(provider.getFactory(ConversationStoreType.IN_MEMORY)).isSameAs(factory2)
            assertThat(provider.availableTypes()).hasSize(1)
        }
    }

    @Nested
    inner class ConversationStoreTypeTests {

        @Test
        fun `enum contains IN_MEMORY type`() {
            assertThat(ConversationStoreType.IN_MEMORY).isNotNull
        }

        @Test
        fun `enum contains STORED type`() {
            assertThat(ConversationStoreType.STORED).isNotNull
        }

        @Test
        fun `enum has exactly two values`() {
            assertThat(ConversationStoreType.entries).hasSize(2)
        }
    }

    @Nested
    inner class ConversationFactoryTests {

        @Test
        fun `create returns conversation with given id`() {
            val factory = TestConversationFactory(ConversationStoreType.IN_MEMORY)

            val conversation = factory.create("test-id")

            assertThat(conversation.id).isEqualTo("test-id")
        }

        @Test
        fun `storeType returns correct type`() {
            val inMemoryFactory = TestConversationFactory(ConversationStoreType.IN_MEMORY)
            val storedFactory = TestConversationFactory(ConversationStoreType.STORED)

            assertThat(inMemoryFactory.storeType).isEqualTo(ConversationStoreType.IN_MEMORY)
            assertThat(storedFactory.storeType).isEqualTo(ConversationStoreType.STORED)
        }

        @Test
        fun `createForParticipants has default implementation that calls create`() {
            val factory = TestConversationFactory(ConversationStoreType.IN_MEMORY)
            val user = TestUser("user-1", "Test User")

            val conversation = factory.createForParticipants(
                id = "conv-id",
                user = user,
                agent = null,
                title = "Test Title"
            )

            assertThat(conversation.id).isEqualTo("conv-id")
        }
    }

    private class TestConversationFactory(
        override val storeType: ConversationStoreType
    ) : ConversationFactory {
        override fun create(id: String): Conversation = InMemoryConversation(id = id)
    }

    private data class TestUser(
        override val id: String,
        override val displayName: String,
        override val username: String = displayName,
        override val email: String? = null
    ) : User
}
