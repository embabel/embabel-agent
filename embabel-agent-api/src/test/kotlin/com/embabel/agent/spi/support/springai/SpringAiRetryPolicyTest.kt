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
import com.embabel.agent.core.NonRetryable
import com.embabel.agent.core.Retryable
import com.embabel.agent.api.tool.TerminateAgentException
import com.embabel.agent.core.ActionException
import com.embabel.agent.core.ReplanRequestedException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.retry.NonTransientAiException
import org.springframework.ai.retry.TransientAiException
import org.springframework.retry.RetryContext
import org.springframework.retry.context.RetryContextSupport
import com.embabel.agent.spi.support.LlmDataBindingProperties
import tools.jackson.databind.DatabindException
import tools.jackson.databind.exc.MismatchedInputException

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
        fun `NonRetryable does not retry`() {
            val exception = ActionException.Permanent("validation error")
            val context = createContext(1, exception)
            assertFalse(policy.canRetry(context))
        }

        @Test
        fun `Retryable retries`() {
            val exception = ActionException.Transient("timeout")
            val context = createContext(1, exception)
            assertTrue(policy.canRetry(context))
        }

        @Test
        fun `custom NonRetryable implementation does not retry`() {
            class CustomNonRetryable(message: String) : RuntimeException(message), NonRetryable

            val exception = CustomNonRetryable("custom error")
            val context = createContext(1, exception)
            assertFalse(policy.canRetry(context))
        }

        @Test
        fun `custom Retryable implementation retries`() {
            class CustomRetryable(message: String) : RuntimeException(message), Retryable

            val exception = CustomRetryable("custom error")
            val context = createContext(1, exception)
            assertTrue(policy.canRetry(context))
        }

        @Test
        fun `wrapped NonRetryable in cause chain does not retry`() {
            val rootCause = ActionException.Permanent("validation error")
            val wrapped = RuntimeException("wrapped", rootCause)
            val context = createContext(1, wrapped)
            assertFalse(policy.canRetry(context), "Wrapped NonRetryable should be detected in cause chain")
        }

        @Test
        fun `wrapped Retryable in cause chain retries`() {
            val rootCause = ActionException.Transient("timeout")
            val wrapped = RuntimeException("wrapped", rootCause)
            val context = createContext(1, wrapped)
            assertTrue(policy.canRetry(context), "Wrapped Retryable should be detected in cause chain")
        }

        @Test
        fun `deeply nested NonRetryable in cause chain does not retry`() {
            val rootCause = ActionException.Permanent("validation error")
            val wrapped1 = RuntimeException("level 1", rootCause)
            val wrapped2 = IllegalStateException("level 2", wrapped1)
            val wrapped3 = RuntimeException("level 3", wrapped2)
            val context = createContext(1, wrapped3)
            assertFalse(policy.canRetry(context), "Deeply nested NonRetryable should be detected")
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
    inner class JacksonDatabindExceptionTests {

        // DatabindException constructors are protected — subclass to instantiate in tests
        private inner class TestDatabindException(msg: String) : DatabindException(msg)

        @Test
        fun `direct DatabindException does not retry`() {
            val context = createContext(1, TestDatabindException("Jackson config error"))
            assertFalse(policy.canRetry(context))
        }

        @Test
        fun `DatabindException wrapped in RuntimeException does not retry`() {
            // Mirrors real production chain: JacksonOutputConverter wraps DatabindException
            // in RuntimeException before it reaches the retry layer
            val wrapped = RuntimeException(TestDatabindException("annotation misconfiguration"))
            val context = createContext(1, wrapped)
            assertFalse(policy.canRetry(context), "DatabindException must be detected even when wrapped in RuntimeException")
        }

        @Test
        fun `MismatchedInputException retries (LLM omitted required field is transient)`() {
            val mismatched = MismatchedInputException.from(null, String::class.java, "missing field")
            val context = createContext(1, mismatched)
            assertTrue(policy.canRetry(context), "MismatchedInputException is retryable — LLM may include field on next attempt")
        }

        @Test
        fun `MismatchedInputException wrapped in RuntimeException retries`() {
            val wrapped = RuntimeException(MismatchedInputException.from(null, String::class.java, "missing field"))
            val context = createContext(1, wrapped)
            assertTrue(policy.canRetry(context))
        }
    }

    @Nested
    inner class HasNonRetryableDatabindExceptionTests {

        private inner class TestDatabindException(msg: String) : DatabindException(msg)

        @Test
        fun `returns true for direct DatabindException`() {
            assert(LlmDataBindingProperties.hasNonRetryableDatabindException(TestDatabindException("config error")))
        }

        @Test
        fun `returns true for DatabindException wrapped in RuntimeException`() {
            val wrapped = RuntimeException(TestDatabindException("config error"))
            assert(LlmDataBindingProperties.hasNonRetryableDatabindException(wrapped))
        }

        @Test
        fun `returns true for deeply nested DatabindException`() {
            val root = TestDatabindException("config error")
            val wrapped = RuntimeException(RuntimeException(root))
            assert(LlmDataBindingProperties.hasNonRetryableDatabindException(wrapped))
        }

        @Test
        fun `returns false for MismatchedInputException`() {
            val mismatched = MismatchedInputException.from(null, String::class.java, "missing field")
            assert(!LlmDataBindingProperties.hasNonRetryableDatabindException(mismatched))
        }

        @Test
        fun `returns false for MismatchedInputException wrapped in RuntimeException`() {
            val wrapped = RuntimeException(MismatchedInputException.from(null, String::class.java, "missing field"))
            assert(!LlmDataBindingProperties.hasNonRetryableDatabindException(wrapped))
        }

        @Test
        fun `returns false for unrelated exception`() {
            assert(!LlmDataBindingProperties.hasNonRetryableDatabindException(RuntimeException("transient")))
        }
    }

    @Nested
    inner class PriorityTests {

        @Test
        fun `explicit NonRetryable marker takes precedence over default retry`() {
            // Even though RuntimeException normally retries, the marker should take precedence
            val exception = ActionException.Permanent("should not retry")
            val context = createContext(1, exception)
            assertFalse(policy.canRetry(context))
        }

        @Test
        fun `explicit Retryable marker takes precedence over programming error check`() {
            // Create a retryable exception that extends IllegalArgumentException
            class RetryableIllegalArg(message: String) : IllegalArgumentException(message), Retryable

            val exception = RetryableIllegalArg("should retry despite being IllegalArgument")
            val context = createContext(1, exception)
            assertTrue(policy.canRetry(context), "Explicit Retryable marker should take precedence")
        }

        @Test
        fun `explicit Retryable marker takes precedence over the databind check`() {
            // The marker is a deliberate declaration by the exception's author; the databind walk is
            // a platform heuristic over a third-party type. Explicit wins, so this pair must retry.
            class TestDatabindException(msg: String) : DatabindException(msg)
            class RetryableWrapper(cause: Throwable) : RuntimeException(cause), Retryable

            val exception = RetryableWrapper(TestDatabindException("annotation misconfiguration"))
            assertTrue(
                LlmDataBindingProperties.hasNonRetryableDatabindException(exception),
                "precondition: the cause chain does carry a non-retryable DatabindException",
            )

            val context = createContext(1, exception)
            assertTrue(policy.canRetry(context), "Explicit Retryable marker should take precedence")
        }
    }
}
