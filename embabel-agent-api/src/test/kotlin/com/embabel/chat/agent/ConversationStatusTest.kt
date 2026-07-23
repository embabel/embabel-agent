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
package com.embabel.chat.agent

import com.embabel.chat.AssistantMessage
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ConversationStatusTest {

    private val objectMapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())

    @Nested
    inner class ConversationContinuesTest {

        @Test
        fun `constructor creates instance with assistant message`() {
            val message = AssistantMessage("Hello!")
            val status = ConversationContinues(message)

            assertThat(status.assistantMessage).isEqualTo(message)
        }

        @Test
        fun `with factory method creates instance`() {
            val message = AssistantMessage("Hi there")
            val status = ConversationContinues.with(message)

            assertThat(status.assistantMessage).isEqualTo(message)
            assertThat(status).isInstanceOf(ConversationStatus::class.java)
        }

        @Test
        fun `equals works correctly`() {
            val message = AssistantMessage("Test")
            val status1 = ConversationContinues(message)
            val status2 = ConversationContinues(message)

            assertThat(status1).isEqualTo(status2)
        }

        @Test
        fun `hashCode is consistent`() {
            val message = AssistantMessage("Test")
            val status1 = ConversationContinues(message)
            val status2 = ConversationContinues(message)

            assertThat(status1.hashCode()).isEqualTo(status2.hashCode())
        }

        @Test
        fun `copy works correctly`() {
            val original = ConversationContinues(AssistantMessage("Original"))
            val newMessage = AssistantMessage("New")
            val copied = original.copy(assistantMessage = newMessage)

            assertThat(copied.assistantMessage).isEqualTo(newMessage)
        }
    }

    @Nested
    inner class ConversationOverTest {

        @Test
        fun `constructor creates instance with reason`() {
            val status = ConversationOver("User requested end")

            assertThat(status.reason).isEqualTo("User requested end")
        }

        @Test
        fun `because factory method creates instance`() {
            val status = ConversationOver.because("Conversation unsafe")

            assertThat(status.reason).isEqualTo("Conversation unsafe")
            assertThat(status).isInstanceOf(ConversationStatus::class.java)
        }

        @Test
        fun `equals works correctly`() {
            val status1 = ConversationOver("reason")
            val status2 = ConversationOver("reason")

            assertThat(status1).isEqualTo(status2)
        }

        @Test
        fun `hashCode is consistent`() {
            val status1 = ConversationOver("reason")
            val status2 = ConversationOver("reason")

            assertThat(status1.hashCode()).isEqualTo(status2.hashCode())
        }

        @Test
        fun `copy works correctly`() {
            val original = ConversationOver("Original reason")
            val copied = original.copy(reason = "New reason")

            assertThat(copied.reason).isEqualTo("New reason")
        }
    }

    @Nested
    inner class PolymorphismTest {

        @Test
        fun `ConversationContinues is a ConversationStatus`() {
            val status: ConversationStatus = ConversationContinues(AssistantMessage("Test"))

            assertThat(status).isInstanceOf(ConversationContinues::class.java)
        }

        @Test
        fun `ConversationOver is a ConversationStatus`() {
            val status: ConversationStatus = ConversationOver("reason")

            assertThat(status).isInstanceOf(ConversationOver::class.java)
        }

        @Test
        fun `can use when expression for exhaustive matching`() {
            val statuses: List<ConversationStatus> = listOf(
                ConversationContinues(AssistantMessage("Hi")),
                ConversationOver("done")
            )

            val results = statuses.map { status ->
                when (status) {
                    is ConversationContinues -> "continues"
                    is ConversationOver -> "over"
                }
            }

            assertThat(results).containsExactly("continues", "over")
        }
    }

    @Nested
    inner class SerializationTest {

        @Test
        fun `ConversationContinues serializes with type info`() {
            val status = ConversationContinues(AssistantMessage("Hello"))
            val json = objectMapper.writeValueAsString(status)

            assertThat(json).contains("\"type\"")
            assertThat(json).contains("ConversationContinues")
        }

        @Test
        fun `ConversationOver serializes with type info`() {
            val status = ConversationOver("done")
            val json = objectMapper.writeValueAsString(status)

            assertThat(json).contains("\"type\"")
            assertThat(json).contains("ConversationOver")
        }

        @Test
        fun `ConversationContinues serialization contains expected fields`() {
            val original = ConversationContinues(AssistantMessage("Test message"))
            val json = objectMapper.writeValueAsString(original)

            assertThat(json).contains("Test message")
            assertThat(json).contains("assistantMessage")
        }

        @Test
        fun `ConversationOver can be deserialized`() {
            val original = ConversationOver("test reason")
            val json = objectMapper.writeValueAsString(original)
            val deserialized = objectMapper.readValue(json, ConversationOver::class.java)

            assertThat(deserialized.reason).isEqualTo("test reason")
        }
    }
}
