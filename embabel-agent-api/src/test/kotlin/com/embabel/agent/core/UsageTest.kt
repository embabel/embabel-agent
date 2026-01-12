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
package com.embabel.agent.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [Usage] data class.
 */
class UsageTest {

    @Nested
    inner class TotalTokensTest {

        @Test
        fun `totalTokens sums prompt and completion tokens`() {
            val usage = Usage(
                promptTokens = 100,
                completionTokens = 50,
                nativeUsage = null,
            )

            assertEquals(150, usage.totalTokens)
        }

        @Test
        fun `totalTokens handles null promptTokens`() {
            val usage = Usage(
                promptTokens = null,
                completionTokens = 50,
                nativeUsage = null,
            )

            assertEquals(50, usage.totalTokens)
        }

        @Test
        fun `totalTokens handles null completionTokens`() {
            val usage = Usage(
                promptTokens = 100,
                completionTokens = null,
                nativeUsage = null,
            )

            assertEquals(100, usage.totalTokens)
        }

        @Test
        fun `totalTokens returns null when both are null`() {
            val usage = Usage(
                promptTokens = null,
                completionTokens = null,
                nativeUsage = null,
            )

            assertNull(usage.totalTokens)
        }
    }

    @Nested
    inner class PlusOperatorTest {

        @Test
        fun `plus combines two Usage instances`() {
            val usage1 = Usage(100, 50, null)
            val usage2 = Usage(200, 75, null)

            val combined = usage1 + usage2

            assertEquals(300, combined.promptTokens)
            assertEquals(125, combined.completionTokens)
        }

        @Test
        fun `plus handles null promptTokens in first operand`() {
            val usage1 = Usage(null, 50, null)
            val usage2 = Usage(200, 75, null)

            val combined = usage1 + usage2

            assertEquals(200, combined.promptTokens)
            assertEquals(125, combined.completionTokens)
        }

        @Test
        fun `plus handles null promptTokens in second operand`() {
            val usage1 = Usage(100, 50, null)
            val usage2 = Usage(null, 75, null)

            val combined = usage1 + usage2

            assertEquals(100, combined.promptTokens)
            assertEquals(125, combined.completionTokens)
        }

        @Test
        fun `plus handles null completionTokens in first operand`() {
            val usage1 = Usage(100, null, null)
            val usage2 = Usage(200, 75, null)

            val combined = usage1 + usage2

            assertEquals(300, combined.promptTokens)
            assertEquals(75, combined.completionTokens)
        }

        @Test
        fun `plus handles null completionTokens in second operand`() {
            val usage1 = Usage(100, 50, null)
            val usage2 = Usage(200, null, null)

            val combined = usage1 + usage2

            assertEquals(300, combined.promptTokens)
            assertEquals(50, combined.completionTokens)
        }

        @Test
        fun `plus returns null promptTokens when both are null`() {
            val usage1 = Usage(null, 50, null)
            val usage2 = Usage(null, 75, null)

            val combined = usage1 + usage2

            assertNull(combined.promptTokens)
            assertEquals(125, combined.completionTokens)
        }

        @Test
        fun `plus returns null completionTokens when both are null`() {
            val usage1 = Usage(100, null, null)
            val usage2 = Usage(200, null, null)

            val combined = usage1 + usage2

            assertEquals(300, combined.promptTokens)
            assertNull(combined.completionTokens)
        }

        @Test
        fun `plus sets nativeUsage to null`() {
            val native1 = object {}
            val native2 = object {}
            val usage1 = Usage(100, 50, native1)
            val usage2 = Usage(200, 75, native2)

            val combined = usage1 + usage2

            assertNull(combined.nativeUsage)
        }

        @Test
        fun `plus is chainable`() {
            val usage1 = Usage(100, 50, null)
            val usage2 = Usage(200, 75, null)
            val usage3 = Usage(50, 25, null)

            val combined = usage1 + usage2 + usage3

            assertEquals(350, combined.promptTokens)
            assertEquals(150, combined.completionTokens)
        }
    }

    @Nested
    inner class DataClassPropertiesTest {

        @Test
        fun `equals works correctly`() {
            val usage1 = Usage(100, 50, null)
            val usage2 = Usage(100, 50, null)
            val usage3 = Usage(100, 51, null)

            assertEquals(usage1, usage2)
            assertNotEquals(usage1, usage3)
        }

        @Test
        fun `hashCode is consistent`() {
            val usage1 = Usage(100, 50, null)
            val usage2 = Usage(100, 50, null)

            assertEquals(usage1.hashCode(), usage2.hashCode())
        }

        @Test
        fun `copy creates modified instance`() {
            val original = Usage(100, 50, null)
            val copied = original.copy(promptTokens = 200)

            assertEquals(200, copied.promptTokens)
            assertEquals(50, copied.completionTokens)
        }
    }
}
