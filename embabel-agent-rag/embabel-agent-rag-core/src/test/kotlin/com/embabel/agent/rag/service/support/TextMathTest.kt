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
package com.embabel.agent.rag.service.support

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class TextMathTest {

    @Nested
    inner class TextMatchScoreTest {

        @Test
        fun `all terms match returns 1`() {
            val text = "kotlin is a modern jvm language"
            val terms = listOf("kotlin", "jvm", "language")

            val score = TextMath.textMatchScore(text, terms)

            assertEquals(1.0, score)
        }

        @Test
        fun `no terms match returns 0`() {
            val text = "kotlin is a modern jvm language"
            val terms = listOf("python", "ruby", "rust")

            val score = TextMath.textMatchScore(text, terms)

            assertEquals(0.0, score)
        }

        @Test
        fun `partial match returns correct ratio`() {
            val text = "kotlin is a modern jvm language"
            val terms = listOf("kotlin", "python", "jvm", "rust")

            val score = TextMath.textMatchScore(text, terms)

            assertEquals(0.5, score) // 2 out of 4 match
        }

        @Test
        fun `empty terms returns 0`() {
            val text = "kotlin is a modern jvm language"
            val terms = emptyList<String>()

            val score = TextMath.textMatchScore(text, terms)

            assertEquals(0.0, score)
        }

        @Test
        fun `empty text returns 0`() {
            val text = ""
            val terms = listOf("kotlin", "jvm")

            val score = TextMath.textMatchScore(text, terms)

            assertEquals(0.0, score)
        }

        @Test
        fun `single term match returns 1`() {
            val text = "kotlin programming language"
            val terms = listOf("kotlin")

            val score = TextMath.textMatchScore(text, terms)

            assertEquals(1.0, score)
        }

        @Test
        fun `single term no match returns 0`() {
            val text = "kotlin programming language"
            val terms = listOf("java")

            val score = TextMath.textMatchScore(text, terms)

            assertEquals(0.0, score)
        }
    }
}
