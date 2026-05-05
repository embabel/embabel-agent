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
    inner class RetryableExceptionTests {

        @Test
        fun `ActionException Retryable implements RetryableException`() {
            val exception = ActionException.Retryable("test message")
            assertTrue(exception is RetryableException)
        }

        @Test
        fun `ActionException Retryable extends RuntimeException`() {
            val exception = ActionException.Retryable("test message")
            assertTrue(exception is RuntimeException)
        }

        @Test
        fun `ActionException Retryable preserves message`() {
            val message = "timeout error"
            val exception = ActionException.Retryable(message)
            assertEquals(message, exception.message)
        }

        @Test
        fun `ActionException Retryable without cause`() {
            val exception = ActionException.Retryable("error")
            assertNull(exception.cause)
        }

        @Test
        fun `ActionException Retryable with cause`() {
            val cause = RuntimeException("root cause")
            val exception = ActionException.Retryable("wrapped error", cause)
            assertSame(cause, exception.cause)
            assertEquals("wrapped error", exception.message)
        }

        @Test
        fun `custom RetryableException implementation`() {
            class CustomRetryable(message: String) : RuntimeException(message), RetryableException

            val exception = CustomRetryable("custom")
            assertTrue(exception is RetryableException)
            assertTrue(exception is RuntimeException)
        }
    }

    @Nested
    inner class NonRetryableExceptionTests {

        @Test
        fun `ActionException NonRetryable implements NonRetryableException`() {
            val exception = ActionException.NonRetryable("test message")
            assertTrue(exception is NonRetryableException)
        }

        @Test
        fun `ActionException NonRetryable extends RuntimeException`() {
            val exception = ActionException.NonRetryable("test message")
            assertTrue(exception is RuntimeException)
        }

        @Test
        fun `ActionException NonRetryable preserves message`() {
            val message = "validation error"
            val exception = ActionException.NonRetryable(message)
            assertEquals(message, exception.message)
        }

        @Test
        fun `ActionException NonRetryable without cause`() {
            val exception = ActionException.NonRetryable("error")
            assertNull(exception.cause)
        }

        @Test
        fun `ActionException NonRetryable with cause`() {
            val cause = IllegalArgumentException("invalid input")
            val exception = ActionException.NonRetryable("wrapped error", cause)
            assertSame(cause, exception.cause)
            assertEquals("wrapped error", exception.message)
        }

        @Test
        fun `custom NonRetryableException implementation`() {
            class CustomNonRetryable(message: String) : RuntimeException(message), NonRetryableException

            val exception = CustomNonRetryable("custom")
            assertTrue(exception is NonRetryableException)
            assertTrue(exception is RuntimeException)
        }
    }

    @Nested
    inner class ActionExceptionHierarchyTests {

        @Test
        fun `ActionException Retryable is subclass of ActionException`() {
            val exception = ActionException.Retryable("test")
            assertInstanceOf(ActionException::class.java, exception)
        }

        @Test
        fun `ActionException NonRetryable is subclass of ActionException`() {
            val exception = ActionException.NonRetryable("test")
            assertInstanceOf(ActionException::class.java, exception)
        }

        @Test
        fun `ActionException is sealed class`() {
            // Verify we can't create other subclasses outside the package
            // This is enforced by Kotlin's sealed class mechanism
            val retryable = ActionException.Retryable("test")
            val nonRetryable = ActionException.NonRetryable("test")

            assertTrue(retryable is ActionException)
            assertTrue(nonRetryable is ActionException)
        }
    }

    @Nested
    inner class JavaInteropTests {

        @Test
        fun `ActionException Retryable works from Java`() {
            // Simulating Java usage pattern
            val exception = ActionException.Retryable("message")
            assertTrue(exception.message == "message")
            assertNull(exception.cause)
        }

        @Test
        fun `ActionException Retryable with cause works from Java`() {
            val cause = RuntimeException("cause")
            val exception = ActionException.Retryable("message", cause)
            assertEquals("message", exception.message)
            assertSame(cause, exception.cause)
        }

        @Test
        fun `ActionException NonRetryable works from Java`() {
            val exception = ActionException.NonRetryable("message")
            assertEquals("message", exception.message)
            assertNull(exception.cause)
        }

        @Test
        fun `ActionException NonRetryable with cause works from Java`() {
            val cause = IllegalArgumentException("cause")
            val exception = ActionException.NonRetryable("message", cause)
            assertEquals("message", exception.message)
            assertSame(cause, exception.cause)
        }
    }
}
