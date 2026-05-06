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

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for ActionException and retry marker interfaces.
 */
class ActionExceptionsTest {

    @Nested
    inner class TransientTests {

        @Test
        fun `ActionException Transient implements Retryable`() {
            val exception = ActionException.Transient("test message")
            assertTrue(exception is Retryable)
        }

        @Test
        fun `ActionException Transient extends RuntimeException`() {
            val exception = ActionException.Transient("test message")
            assertTrue(exception is RuntimeException)
        }

        @Test
        fun `ActionException Transient preserves message`() {
            val message = "timeout error"
            val exception = ActionException.Transient(message)
            assertEquals(message, exception.message)
        }

        @Test
        fun `ActionException Transient without cause`() {
            val exception = ActionException.Transient("error")
            assertNull(exception.cause)
        }

        @Test
        fun `ActionException Transient with cause`() {
            val cause = RuntimeException("root cause")
            val exception = ActionException.Transient("wrapped error", cause)
            assertSame(cause, exception.cause)
            assertEquals("wrapped error", exception.message)
        }

        @Test
        fun `custom Retryable implementation`() {
            class CustomRetryable(message: String) : RuntimeException(message), Retryable

            val exception = CustomRetryable("custom")
            assertTrue(exception is Retryable)
            assertTrue(exception is RuntimeException)
        }
    }

    @Nested
    inner class PermanentTests {

        @Test
        fun `ActionException Permanent implements NonRetryable`() {
            val exception = ActionException.Permanent("test message")
            assertTrue(exception is NonRetryable)
        }

        @Test
        fun `ActionException Permanent extends RuntimeException`() {
            val exception = ActionException.Permanent("test message")
            assertTrue(exception is RuntimeException)
        }

        @Test
        fun `ActionException Permanent preserves message`() {
            val message = "validation error"
            val exception = ActionException.Permanent(message)
            assertEquals(message, exception.message)
        }

        @Test
        fun `ActionException Permanent without cause`() {
            val exception = ActionException.Permanent("error")
            assertNull(exception.cause)
        }

        @Test
        fun `ActionException Permanent with cause`() {
            val cause = IllegalArgumentException("invalid input")
            val exception = ActionException.Permanent("wrapped error", cause)
            assertSame(cause, exception.cause)
            assertEquals("wrapped error", exception.message)
        }

        @Test
        fun `custom NonRetryable implementation`() {
            class CustomNonRetryable(message: String) : RuntimeException(message), NonRetryable

            val exception = CustomNonRetryable("custom")
            assertTrue(exception is NonRetryable)
            assertTrue(exception is RuntimeException)
        }
    }

    @Nested
    inner class ActionExceptionHierarchyTests {

        @Test
        fun `ActionException Transient is subclass of ActionException`() {
            val exception = ActionException.Transient("test")
            assertInstanceOf(ActionException::class.java, exception)
        }

        @Test
        fun `ActionException Permanent is subclass of ActionException`() {
            val exception = ActionException.Permanent("test")
            assertInstanceOf(ActionException::class.java, exception)
        }

        @Test
        fun `ActionException is open class allowing extension`() {
            // ActionException is open to allow users to create domain-specific hierarchies
            val transient = ActionException.Transient("test")
            val permanent = ActionException.Permanent("test")

            assertTrue(transient is ActionException)
            assertTrue(permanent is ActionException)
        }
    }

    @Nested
    inner class JavaInteropTests {

        @Test
        fun `ActionException Transient works from Java`() {
            // Simulating Java usage pattern
            val exception = ActionException.Transient("message")
            assertTrue(exception.message == "message")
            assertNull(exception.cause)
        }

        @Test
        fun `ActionException Transient with cause works from Java`() {
            val cause = RuntimeException("cause")
            val exception = ActionException.Transient("message", cause)
            assertEquals("message", exception.message)
            assertSame(cause, exception.cause)
        }

        @Test
        fun `ActionException Permanent works from Java`() {
            val exception = ActionException.Permanent("message")
            assertEquals("message", exception.message)
            assertNull(exception.cause)
        }

        @Test
        fun `ActionException Permanent with cause works from Java`() {
            val cause = IllegalArgumentException("cause")
            val exception = ActionException.Permanent("message", cause)
            assertEquals("message", exception.message)
            assertSame(cause, exception.cause)
        }
    }
}
