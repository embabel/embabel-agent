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

import com.embabel.agent.core.support.InMemoryBlackboard
import com.embabel.agent.domain.io.UserInput
import com.embabel.agent.domain.library.HasContent
import com.embabel.chat.AssistantMessage
import com.embabel.chat.UserMessage
import com.embabel.chat.support.InMemoryConversation
import com.embabel.common.core.types.HasInfoString
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class BlackboardFormatterTest {

    @Nested
    inner class DefaultBlackboardEntryFormatterTest {

        @Test
        fun `formats HasInfoString using infoString`() {
            val entry = object : HasInfoString {
                override fun infoString(verbose: Boolean?, indent: Int): String =
                    "InfoString[verbose=$verbose]"
            }

            val result = DefaultBlackboardEntryFormatter.format(entry)

            assertThat(result).isEqualTo("InfoString[verbose=true]")
        }

        @Test
        fun `formats HasContent using content property`() {
            val entry = object : HasContent {
                override val content: String = "Test content"
            }

            val result = DefaultBlackboardEntryFormatter.format(entry)

            assertThat(result).isEqualTo("Test content")
        }

        @Test
        fun `formats regular object using toString`() {
            data class SimpleObject(val name: String)
            val entry = SimpleObject("test")

            val result = DefaultBlackboardEntryFormatter.format(entry)

            assertThat(result).isEqualTo("SimpleObject(name=test)")
        }

        @Test
        fun `HasInfoString takes precedence over HasContent`() {
            val entry = object : HasInfoString, HasContent {
                override fun infoString(verbose: Boolean?, indent: Int): String = "InfoString"
                override val content: String = "Content"
            }

            val result = DefaultBlackboardEntryFormatter.format(entry)

            assertThat(result).isEqualTo("InfoString")
        }
    }

    @Nested
    inner class DefaultBlackboardFormatterTest {

        private val formatter = DefaultBlackboardFormatter()

        @Test
        fun `empty blackboard returns empty string`() {
            val blackboard = InMemoryBlackboard()

            val result = formatter.format(blackboard)

            assertThat(result).isEmpty()
        }

        @Test
        fun `excludes Conversation from output`() {
            val blackboard = InMemoryBlackboard()
            blackboard += InMemoryConversation()

            val result = formatter.format(blackboard)

            assertThat(result).isEmpty()
        }

        @Test
        fun `excludes UserInput from output`() {
            val blackboard = InMemoryBlackboard()
            blackboard += UserInput("test input")

            val result = formatter.format(blackboard)

            assertThat(result).isEmpty()
        }

        @Test
        fun `excludes Message from output`() {
            val blackboard = InMemoryBlackboard()
            blackboard += UserMessage("test message")
            blackboard += AssistantMessage("assistant response")

            val result = formatter.format(blackboard)

            assertThat(result).isEmpty()
        }

        @Test
        fun `formats regular objects`() {
            data class TestObject(val value: String)
            val blackboard = InMemoryBlackboard()
            blackboard += TestObject("item1")

            val result = formatter.format(blackboard)

            assertThat(result).contains("TestObject(value=item1)")
        }

        @Test
        fun `formats multiple objects joined by newlines`() {
            data class Item(val id: Int)
            val blackboard = InMemoryBlackboard()
            blackboard += Item(1)
            blackboard += Item(2)

            val result = formatter.format(blackboard)

            assertThat(result).contains("Item(id=1)")
            assertThat(result).contains("Item(id=2)")
            assertThat(result.split("\n")).hasSize(2)
        }

        @Test
        fun `trims whitespace from formatted entries`() {
            val blackboard = InMemoryBlackboard()
            blackboard += object : HasContent {
                override val content: String = "  padded content  "
            }

            val result = formatter.format(blackboard)

            assertThat(result).isEqualTo("padded content")
        }

        @Test
        fun `uses custom entry formatter when provided`() {
            val customFormatter = object : BlackboardEntryFormatter {
                override fun format(entry: Any): String = "custom:${entry.javaClass.simpleName}"
            }
            val formatter = DefaultBlackboardFormatter(customFormatter)
            data class MyClass(val x: Int)
            val blackboard = InMemoryBlackboard()
            blackboard += MyClass(1)

            val result = formatter.format(blackboard)

            assertThat(result).isEqualTo("custom:MyClass")
        }

        @Test
        fun `excludes conversation related objects but includes others`() {
            data class DomainObject(val data: String)
            val blackboard = InMemoryBlackboard()
            blackboard += InMemoryConversation()
            blackboard += UserInput("input")
            blackboard += UserMessage("user msg")
            blackboard += DomainObject("important data")

            val result = formatter.format(blackboard)

            assertThat(result).contains("DomainObject")
            assertThat(result).doesNotContain("Conversation")
            assertThat(result).doesNotContain("UserInput")
            assertThat(result).doesNotContain("user msg")
        }
    }
}
