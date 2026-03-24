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
package com.embabel.common.ai.model

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TokenCounterTest {

    data class SimpleMessage(val role: String, val content: String)

    @Nested
    inner class Contract {

        @Test
        fun `countTokens returns 0 for empty string`() {
            val counter: TokenCounter<String> = TokenCounter { it.length / 4 }
            assertEquals(0, counter.countTokens(""))
        }

        @Test
        fun `countTokens returns non-negative for any input`() {
            val counter: TokenCounter<String> = TokenCounter { it.length / 4 }
            assertTrue(counter.countTokens("hello world") >= 0)
        }

        @Test
        fun `countTokens returns positive for non-empty text`() {
            val counter: TokenCounter<String> = TokenCounter { maxOf(1, it.length / 4) }
            assertTrue(counter.countTokens("hello world") > 0)
        }
    }

    @Nested
    inner class HeuristicFactory {

        @Test
        fun `heuristic returns a TokenCounter`() {
            val counter = TokenCounter.heuristic()
            assertNotNull(counter)
        }

        @Test
        fun `heuristic returns default CharacterHeuristicTokenCounter`() {
            assertSame(CharacterHeuristicTokenCounter.DEFAULT, TokenCounter.heuristic())
        }
    }

    @Nested
    inner class LambdaCreation {

        @Test
        fun `fun interface supports lambda creation`() {
            val counter: TokenCounter<String> = TokenCounter { it.length / 3 }
            assertEquals(3, counter.countTokens("123456789"))
        }
    }

    @Nested
    inner class GenericTypeParameter {

        @Test
        fun `supports non-String content types`() {
            val counter: TokenCounter<SimpleMessage> = TokenCounter { msg ->
                msg.content.length / 4
            }
            assertEquals(2, counter.countTokens(SimpleMessage("user", "abcdefgh")))
        }

        @Test
        fun `message counter can compose with string counter`() {
            val textCounter = TokenCounter.heuristic()
            val messageCounter: TokenCounter<SimpleMessage> = TokenCounter { msg ->
                textCounter.countTokens(msg.content)
            }
            assertEquals(2, messageCounter.countTokens(SimpleMessage("user", "abcdefgh")))
        }
    }
}
