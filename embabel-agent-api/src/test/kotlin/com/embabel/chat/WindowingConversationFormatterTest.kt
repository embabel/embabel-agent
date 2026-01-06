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

class WindowingConversationFormatterTest {

    @Test
    fun empty() {
        val formatter = WindowingConversationFormatter(windowSize = 2)
        val conversation = InMemoryConversation()
        val formatted = formatter.format(conversation)
        assertEquals("", formatted)
    }

    @Test
    fun `does not cut off`() {
        val formatter = WindowingConversationFormatter(windowSize = 20)
        val conversation = InMemoryConversation()
        conversation.addMessage(UserMessage("hello", "Bill"))
        conversation.addMessage(AssistantMessage("Hi there!"))
        conversation.addMessage(UserMessage("How are you?", "Bill"))
        conversation.addMessage(UserMessage("I'm great", "Bill"))
        val formatted = formatter.format(conversation)
        assertTrue(formatted.contains("Bill"))
        assertTrue(formatted.contains("Hi there!"))
        assertTrue(formatted.contains("How are you?"))
        assertTrue(formatted.contains("I'm great"))
    }

    @Test
    fun `cuts off`() {
        val formatter = WindowingConversationFormatter(windowSize = 2)
        val conversation = InMemoryConversation()
        conversation.addMessage(UserMessage("hello", "Bill"))
        conversation.addMessage(AssistantMessage("Hi there!"))
        conversation.addMessage(UserMessage("How are you?", "Bill"))
        conversation.addMessage(UserMessage("I'm great", "Bill"))
        val formatted = formatter.format(conversation)
        assertTrue(formatted.contains("Bill"))
        assertFalse(formatted.contains("Hi there!"), "Should cut off first message:\n$formatted")
        assertTrue(formatted.contains("How are you?"))
        assertTrue(formatted.contains("I'm great"))
    }

}
