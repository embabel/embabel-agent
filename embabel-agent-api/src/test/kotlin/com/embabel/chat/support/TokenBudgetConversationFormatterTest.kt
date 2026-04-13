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

import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.chat.MessageFormatter
import com.embabel.chat.MessageRole
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.TokenCountEstimator
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TokenBudgetConversationFormatterTest {

    private val contentLengthEstimator: TokenCountEstimator<Message> = TokenCountEstimator { it.content.length }

    @Nested
    inner class MessageSelectionTests {

        @Test
        fun `includes most recent messages that fit within budget`() {
            // content lengths: "alpha" = 5, "beta" = 4, "gamma" = 5
            val formatter = TokenBudgetConversationFormatter(
                tokenCountEstimator = contentLengthEstimator,
                tokenBudget = 10,
            )
            val conversation = InMemoryConversation()
            conversation.addMessage(UserMessage("alpha", "X"))
            conversation.addMessage(UserMessage("beta", "X"))
            conversation.addMessage(AssistantMessage("gamma"))
            val formatted = formatter.format(conversation)
            // Budget 10: "gamma" (5) + "beta" (4) = 9, next "alpha" (5) > 1 remaining
            assertFalse(formatted.contains("alpha"), "First message should be excluded")
            assertTrue(formatted.contains("beta"))
            assertTrue(formatted.contains("gamma"))
        }

        @Test
        fun `includes all messages when budget is sufficient`() {
            val formatter = TokenBudgetConversationFormatter(
                tokenCountEstimator = contentLengthEstimator,
                tokenBudget = 1000,
            )
            val conversation = InMemoryConversation()
            conversation.addMessage(UserMessage("alpha", "X"))
            conversation.addMessage(UserMessage("beta", "X"))
            conversation.addMessage(AssistantMessage("gamma"))
            val formatted = formatter.format(conversation)
            assertTrue(formatted.contains("alpha"))
            assertTrue(formatted.contains("beta"))
            assertTrue(formatted.contains("gamma"))
        }

        @Test
        fun `tokenCountEstimator receives Message allowing framing-aware estimation`() {
            val framingAwareEstimator: TokenCountEstimator<Message> = TokenCountEstimator { msg ->
                val overhead = if (msg.role == MessageRole.ASSISTANT) 10 else 5
                msg.content.length + overhead
            }
            val formatter = TokenBudgetConversationFormatter(
                tokenCountEstimator = framingAwareEstimator,
                tokenBudget = 20,
            )
            val conversation = InMemoryConversation()
            conversation.addMessage(UserMessage("hi", "X"))         // 2 + 5 = 7
            conversation.addMessage(AssistantMessage("hello"))       // 5 + 10 = 15
            val formatted = formatter.format(conversation)
            // Budget 20: "hello" (15) fits, "hi" (7) > 5 remaining
            assertTrue(formatted.contains("hello"))
            assertFalse(formatted.contains(": hi"), "User message should be excluded due to framing overhead")
        }
    }

    @Nested
    inner class EmptyConversationTests {

        @Test
        fun `returns empty when budget is zero`() {
            val formatter = TokenBudgetConversationFormatter(
                tokenCountEstimator = contentLengthEstimator,
                tokenBudget = 0,
            )
            val conversation = InMemoryConversation()
            conversation.addMessage(UserMessage("hello", "Bill"))
            val formatted = formatter.format(conversation)
            assertEquals("", formatted)
        }

        @Test
        fun `returns empty for empty conversation`() {
            val formatter = TokenBudgetConversationFormatter(
                tokenCountEstimator = contentLengthEstimator,
                tokenBudget = 1000,
            )
            val conversation = InMemoryConversation()
            val formatted = formatter.format(conversation)
            assertEquals("", formatted)
        }
    }

    @Nested
    inner class ConfigurationTests {

        @Test
        fun `respects startIndex`() {
            val formatter = TokenBudgetConversationFormatter(
                tokenCountEstimator = contentLengthEstimator,
                tokenBudget = 1000,
                startIndex = 1,
            )
            val conversation = InMemoryConversation()
            conversation.addMessage(UserMessage("first", "X"))
            conversation.addMessage(UserMessage("second", "X"))
            conversation.addMessage(UserMessage("third", "X"))
            val formatted = formatter.format(conversation)
            assertFalse(formatted.contains("first"), "First message should be skipped by startIndex")
            assertTrue(formatted.contains("second"))
            assertTrue(formatted.contains("third"))
        }

        @Test
        fun `uses custom message formatter`() {
            val customFormatter = MessageFormatter { msg -> ">> ${msg.content}" }
            val formatter = TokenBudgetConversationFormatter(
                messageFormatter = customFormatter,
                tokenCountEstimator = contentLengthEstimator,
                tokenBudget = 1000,
            )
            val conversation = InMemoryConversation()
            conversation.addMessage(UserMessage("hello", "Bill"))
            val formatted = formatter.format(conversation)
            assertTrue(formatted.startsWith(">> hello"))
        }
    }
}
