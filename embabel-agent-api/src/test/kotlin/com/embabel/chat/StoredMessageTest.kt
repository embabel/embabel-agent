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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Tests for [StoredMessage] and [MessageRole].
 */
class StoredMessageTest {

    @Nested
    inner class MessageRoleTests {

        @Test
        fun `enum has USER role`() {
            assertThat(MessageRole.USER).isNotNull
            assertThat(MessageRole.USER.name).isEqualTo("USER")
        }

        @Test
        fun `enum has ASSISTANT role`() {
            assertThat(MessageRole.ASSISTANT).isNotNull
            assertThat(MessageRole.ASSISTANT.name).isEqualTo("ASSISTANT")
        }

        @Test
        fun `enum has SYSTEM role`() {
            assertThat(MessageRole.SYSTEM).isNotNull
            assertThat(MessageRole.SYSTEM.name).isEqualTo("SYSTEM")
        }

        @Test
        fun `enum has exactly three values`() {
            assertThat(MessageRole.entries).hasSize(3)
            assertThat(MessageRole.entries).containsExactlyInAnyOrder(
                MessageRole.USER,
                MessageRole.ASSISTANT,
                MessageRole.SYSTEM
            )
        }

        @Test
        fun `valueOf returns correct enum for valid names`() {
            assertThat(MessageRole.valueOf("USER")).isEqualTo(MessageRole.USER)
            assertThat(MessageRole.valueOf("ASSISTANT")).isEqualTo(MessageRole.ASSISTANT)
            assertThat(MessageRole.valueOf("SYSTEM")).isEqualTo(MessageRole.SYSTEM)
        }
    }

    @Nested
    inner class StoredMessageInterfaceTests {

        @Test
        fun `Message classes implement StoredMessage`() {
            val userMessage = UserMessage("Hello")
            val assistantMessage = AssistantMessage("Hi there")
            val systemMessage = SystemMessage("System prompt")

            assertThat(userMessage).isInstanceOf(StoredMessage::class.java)
            assertThat(assistantMessage).isInstanceOf(StoredMessage::class.java)
            assertThat(systemMessage).isInstanceOf(StoredMessage::class.java)
        }

        @Test
        fun `UserMessage provides correct StoredMessage properties`() {
            val timestamp = Instant.now()
            val message = UserMessage("Hello world", timestamp = timestamp)

            assertThat(message.role).isEqualTo(MessageRole.USER)
            assertThat(message.content).isEqualTo("Hello world")
            assertThat(message.timestamp).isEqualTo(timestamp)
        }

        @Test
        fun `AssistantMessage provides correct StoredMessage properties`() {
            val timestamp = Instant.now()
            val message = AssistantMessage("Hello user", timestamp = timestamp)

            assertThat(message.role).isEqualTo(MessageRole.ASSISTANT)
            assertThat(message.content).isEqualTo("Hello user")
            assertThat(message.timestamp).isEqualTo(timestamp)
        }

        @Test
        fun `SystemMessage provides correct StoredMessage properties`() {
            val timestamp = Instant.now()
            val message = SystemMessage("Be helpful", timestamp = timestamp)

            assertThat(message.role).isEqualTo(MessageRole.SYSTEM)
            assertThat(message.content).isEqualTo("Be helpful")
            assertThat(message.timestamp).isEqualTo(timestamp)
        }
    }

    @Nested
    inner class RoleTypeAliasTests {

        @Test
        fun `Role typealias equals MessageRole`() {
            assertThat(Role.USER).isEqualTo(MessageRole.USER)
            assertThat(Role.ASSISTANT).isEqualTo(MessageRole.ASSISTANT)
            assertThat(Role.SYSTEM).isEqualTo(MessageRole.SYSTEM)
        }
    }
}
