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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.api.tool.TerminateActionException
import com.embabel.agent.api.tool.TerminateAgentException
import com.embabel.agent.core.ActionException
import com.embabel.agent.core.NonRetryableException
import com.embabel.agent.core.ReplanRequestedException
import com.embabel.agent.core.RetryableException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.retry.RetryContext
import org.springframework.retry.context.RetryContextSupport

/**
 * Tests for SpringAiRetryPolicy retry decision logic.
 */
class SpringAiRetryPolicyTest {

    private val policy = SpringAiRetryPolicy(maxAttempts = 3)

    private fun createContext(retryCount: Int, throwable: Throwable?): RetryContext {
        val context = RetryContextSupport(null)
        repeat(retryCount) {
            context.registerThrowable(throwable)
        }
        return context
    }

    @Test
    fun `first attempt always retries`() {
        val context = createContext(0, null)
        assertTrue(policy.canRetry(context))
    }

    @Test
    fun `exceeding maxAttempts stops retry`() {
        val context = createContext(3, RuntimeException("error"))
        assertFalse(policy.canRetry(context))
    }

    @Nested
    inner class ControlFlowSignalsTests {

        @Test
        fun `TerminateActionException does not retry`() {
            val context = createContext(1, TerminateActionException("test"))
            assertFalse(policy.canRetry(context))
        }

        @Test
        fun `TerminateAgentException does not retry`() {
            val context = createContext(1, TerminateAgentException("test"))
            assertFalse(policy.canRetry(context))
        }

        @Test
        fun `ReplanRequestedException does not retry`() {
            val context = createContext(1, ReplanRequestedException("test"))
            assertFalse(policy.canRetry(context))
        }
    }

    @Nested
    inner class ExplicitMarkersTests {

        @Test
        fun `NonRetryableException does not retry`() {
            val exception = ActionException.NonRetryable("validation error")
            val context = createContext(1, exception)
            assertFalse(policy.canRetry(context))
        }

        @Test
        fun `RetryableException retries`() {
            val exception = ActionException.Retryable("timeout")
            val context = createContext(1, exception)
            assertTrue(policy.canRetry(context))
        }

        @Test
        fun `custom NonRetryableException implementation does not retry`() {
            class CustomNonRetryable(message: String) : RuntimeException(message), NonRetryableException

            val exception = CustomNonRetryable("custom error")
            val context = createContext(1, exception)
            assertFalse(policy.canRetry(context))
        }

        @Test
        fun `custom RetryableException implementation retries`() {
            class CustomRetryable(message: String) : RuntimeException(message), RetryableException

            val exception = CustomRetryable("custom error")
            val context = createContext(1, exception)
            assertTrue(policy.canRetry(context))
        }

        @Test
        fun `wrapped NonRetryableException in cause chain does not retry`() {
            val rootCause = ActionException.NonRetryable("validation error")
            val wrapped = RuntimeException("wrapped", rootCause)
            val context = createContext(1, wrapped)
            assertFalse(policy.canRetry(context), "Wrapped NonRetryableException should be detected in cause chain")
        }

        @Test
        fun `wrapped RetryableException in cause chain retries`() {
            val rootCause = ActionException.Retryable("timeout")
            val wrapped = RuntimeException("wrapped", rootCause)
            val context = createContext(1, wrapped)
            assertTrue(policy.canRetry(context), "Wrapped RetryableException should be detected in cause chain")
        }

        @Test
        fun `deeply nested NonRetryableException in cause chain does not retry`() {
            val rootCause = ActionException.NonRetryable("validation error")
            val wrapped1 = RuntimeException("level 1", rootCause)
            val wrapped2 = IllegalStateException("level 2", wrapped1)
            val wrapped3 = RuntimeException("level 3", wrapped2)
            val context = createContext(1, wrapped3)
            assertFalse(policy.canRetry(context), "Deeply nested NonRetryableException should be detected")
        }
    }

    @Nested
    inner class SpringAiExceptionsTests {

        @Test
        fun `TransientAiException retries`() {
            val exception = TransientAiException("transient error")
            val context = createContext(1, exception)
            assertTrue(policy.canRetry(context))
        }

        @Test
        fun `NonTransientAiException without rate limit does not retry`() {
            val exception = NonTransientAiException("permanent error")
            val context = createContext(1, exception)
            assertFalse(policy.canRetry(context))
        }

        @Test
        fun `NonTransientAiException with rate limit retries`() {
            val exception = NonTransientAiException("rate limit exceeded")
            val context = createContext(1, exception)
            assertTrue(policy.canRetry(context))
        }

        @Test
        fun `NonTransientAiException with rate-limit retries`() {
            val exception = NonTransientAiException("rate-limit error")
            val context = createContext(1, exception)
            assertTrue(policy.canRetry(context))
        }
    }

    @Nested
    inner class ProgrammingErrorsTests {

        @Test
        fun `IllegalArgumentException does not retry`() {
            val exception = IllegalArgumentException("invalid argument")
            val context = createContext(1, exception)
            assertFalse(policy.canRetry(context))
        }

        @Test
        fun `IllegalStateException does not retry`() {
            val exception = IllegalStateException("invalid state")
            val context = createContext(1, exception)
            assertFalse(policy.canRetry(context))
        }

        @Test
        fun `UnsupportedOperationException does not retry`() {
            val exception = UnsupportedOperationException("not supported")
            val context = createContext(1, exception)
            assertFalse(policy.canRetry(context))
        }

        @Test
        fun `NullPointerException does not retry`() {
            val exception = NullPointerException("null reference")
            val context = createContext(1, exception)
            assertFalse(policy.canRetry(context))
        }

        @Test
        fun `ClassCastException does not retry`() {
            val exception = ClassCastException("invalid cast")
            val context = createContext(1, exception)
            assertFalse(policy.canRetry(context))
        }
    }

    @Nested
    inner class BackwardCompatibilityTests {

        @Test
        fun `unknown exception retries by default`() {
            class CustomException(message: String) : RuntimeException(message)

            val exception = CustomException("unknown error")
            val context = createContext(1, exception)
            assertTrue(policy.canRetry(context), "Unknown exceptions should retry for backward compatibility")
        }

        @Test
        fun `IOException retries by default`() {
            val exception = java.io.IOException("network error")
            val context = createContext(1, exception)
            assertTrue(policy.canRetry(context))
        }

        @Test
        fun `generic RuntimeException retries by default`() {
            val exception = RuntimeException("generic error")
            val context = createContext(1, exception)
            assertTrue(policy.canRetry(context))
        }
    }

    @Nested
    inner class PriorityTests {

        @Test
        fun `explicit NonRetryableException marker takes precedence over default retry`() {
            // Even though RuntimeException normally retries, the marker should take precedence
            val exception = ActionException.NonRetryable("should not retry")
            val context = createContext(1, exception)
            assertFalse(policy.canRetry(context))
        }

        @Test
        fun `explicit RetryableException marker takes precedence over programming error check`() {
            // Create a retryable exception that extends IllegalArgumentException
            class RetryableIllegalArg(message: String) : IllegalArgumentException(message), RetryableException

            val exception = RetryableIllegalArg("should retry despite being IllegalArgument")
            val context = createContext(1, exception)
            assertTrue(policy.canRetry(context), "Explicit RetryableException marker should take precedence")
        }
    }
}
