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
package com.embabel.agent.api.tool

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ToolCallContextTest {

    @Nested
    inner class BasicOperations {

        @Test
        fun `EMPTY context has no entries`() {
            assertTrue(ToolCallContext.EMPTY.isEmpty)
            assertNull(ToolCallContext.EMPTY.get<String>("anything"))
        }

        @Test
        fun `of creates context from pairs`() {
            val ctx = ToolCallContext.of("key" to "value", "num" to 42)
            assertEquals("value", ctx.get<String>("key"))
            assertEquals(42, ctx.get<Int>("num"))
            assertFalse(ctx.isEmpty)
        }

        @Test
        fun `of creates context from map`() {
            val ctx = ToolCallContext.of(mapOf("token" to "abc123"))
            assertEquals("abc123", ctx.get<String>("token"))
        }

        @Test
        fun `get returns null for missing key`() {
            val ctx = ToolCallContext.of("a" to 1)
            assertNull(ctx.get<String>("missing"))
        }

        @Test
        fun `contains checks key presence`() {
            val ctx = ToolCallContext.of("present" to true)
            assertTrue("present" in ctx)
            assertFalse("absent" in ctx)
        }

        @Test
        fun `getOrDefault returns default for missing key`() {
            val ctx = ToolCallContext.of("a" to 1)
            assertEquals("fallback", ctx.getOrDefault("missing", "fallback"))
            assertEquals(1, ctx.getOrDefault("a", 99))
        }

        @Test
        fun `toMap returns defensive copy`() {
            val ctx = ToolCallContext.of("k" to "v")
            val map = ctx.toMap()
            assertEquals(mapOf("k" to "v"), map)
        }
    }

    @Nested
    inner class MergeTests {

        @Test
        fun `merge combines two contexts`() {
            val a = ToolCallContext.of("x" to 1)
            val b = ToolCallContext.of("y" to 2)
            val merged = a.merge(b)
            assertEquals(1, merged.get<Int>("x"))
            assertEquals(2, merged.get<Int>("y"))
        }

        @Test
        fun `merge gives other precedence on conflict`() {
            val base = ToolCallContext.of("key" to "old")
            val override = ToolCallContext.of("key" to "new")
            val merged = base.merge(override)
            assertEquals("new", merged.get<String>("key"))
        }

        @Test
        fun `merge with EMPTY returns original`() {
            val ctx = ToolCallContext.of("a" to 1)
            val merged = ctx.merge(ToolCallContext.EMPTY)
            assertEquals(ctx, merged)
        }
    }

    @Nested
    inner class ThreadLocalHolder {

        @Test
        fun `current returns EMPTY when nothing set`() {
            ToolCallContext.remove()
            assertEquals(ToolCallContext.EMPTY, ToolCallContext.current())
        }

        @Test
        fun `set and current round-trip`() {
            val ctx = ToolCallContext.of("token" to "secret")
            try {
                ToolCallContext.set(ctx)
                assertEquals(ctx, ToolCallContext.current())
                assertEquals("secret", ToolCallContext.current().get<String>("token"))
            } finally {
                ToolCallContext.remove()
            }
        }

        @Test
        fun `remove clears the holder`() {
            ToolCallContext.set(ToolCallContext.of("a" to 1))
            ToolCallContext.remove()
            assertEquals(ToolCallContext.EMPTY, ToolCallContext.current())
        }
    }
}
