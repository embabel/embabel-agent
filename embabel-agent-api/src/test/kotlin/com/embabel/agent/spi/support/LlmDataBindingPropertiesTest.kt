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
package com.embabel.agent.spi.support

import com.embabel.agent.core.Blackboard
import com.embabel.agent.core.ReplanRequestedException
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import tools.jackson.databind.DatabindException
import tools.jackson.databind.exc.MismatchedInputException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@ExtendWith(OutputCaptureExtension::class)
class LlmDataBindingPropertiesTest {

    @Nested
    inner class RetryTemplateTest {

        @Test
        fun `retryTemplate does not retry on ReplanRequestedException`() {
            val properties = LlmDataBindingProperties(maxAttempts = 3)
            val retryTemplate = properties.retryTemplate("test")

            var attemptCount = 0
            val exception = assertThrows<ReplanRequestedException> {
                retryTemplate.execute<Unit, ReplanRequestedException> {
                    attemptCount++
                    throw ReplanRequestedException(
                        reason = "Replan needed",
                        blackboardUpdater = { bb -> bb["key"] = "value" }
                    )
                }
            }

            // Should only attempt once - no retries for ReplanRequestedException
            assertEquals(1, attemptCount)
            assertEquals("Replan needed", exception.reason)
            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            exception.blackboardUpdater.accept(mockBlackboard)
            verify { mockBlackboard["key"] = "value" }
        }

        @Test
        fun `retryTemplate retries on regular exceptions`() {
            val properties = LlmDataBindingProperties(maxAttempts = 3)
            val retryTemplate = properties.retryTemplate("test")

            var attemptCount = 0
            assertThrows<RuntimeException> {
                retryTemplate.execute<Unit, RuntimeException> {
                    attemptCount++
                    throw RuntimeException("Transient error")
                }
            }

            // Should attempt maxAttempts times for regular exceptions
            assertEquals(3, attemptCount)
        }

        @Test
        fun `retryTemplate propagates blackboard updater from ReplanRequestedException`() {
            val properties = LlmDataBindingProperties(maxAttempts = 5)
            val retryTemplate = properties.retryTemplate("test")

            val exception = assertThrows<ReplanRequestedException> {
                retryTemplate.execute<Unit, ReplanRequestedException> {
                    throw ReplanRequestedException(
                        reason = "Routing decision",
                        blackboardUpdater = { bb ->
                            bb["intent"] = "support"
                            bb["confidence"] = 0.95
                            bb["target"] = "handleSupport"
                        }
                    )
                }
            }

            assertEquals("Routing decision", exception.reason)
            val mockBlackboard = mockk<Blackboard>(relaxed = true)
            exception.blackboardUpdater.accept(mockBlackboard)
            verify { mockBlackboard["intent"] = "support" }
            verify { mockBlackboard["confidence"] = 0.95 }
            verify { mockBlackboard["target"] = "handleSupport" }
        }

        // DatabindException constructors are protected — subclass to instantiate in tests
        private inner class TestDatabindException(msg: String) : DatabindException(msg)

        @Test
        fun `retryTemplate aborts immediately on DatabindException — not retried`() {
            val properties = LlmDataBindingProperties(maxAttempts = 5)
            val retryTemplate = properties.retryTemplate("test")
            var attemptCount = 0
            assertThrows<TestDatabindException> {
                retryTemplate.execute<Unit, Exception> {
                    attemptCount++
                    throw TestDatabindException("wrong @JsonDeserialize annotation")
                }
            }
            assertEquals(1, attemptCount, "DatabindException must not be retried")
        }

        @Test
        fun `retryTemplate aborts immediately on DatabindException wrapped in RuntimeException`() {
            // Mirrors real production chain: JacksonOutputConverter wraps DatabindException
            // in RuntimeException before it reaches the retry layer
            val properties = LlmDataBindingProperties(maxAttempts = 5)
            val retryTemplate = properties.retryTemplate("test")
            var attemptCount = 0
            val root = TestDatabindException("annotation misconfiguration")
            assertThrows<RuntimeException> {
                retryTemplate.execute<Unit, Exception> {
                    attemptCount++
                    throw RuntimeException(root)
                }
            }
            assertEquals(1, attemptCount, "Wrapped DatabindException must be detected via cause-chain walk and not retried")
        }

        @Test
        fun `retryTemplate retries on MismatchedInputException (LLM omitted required field)`() {
            val properties = LlmDataBindingProperties(maxAttempts = 3)
            val retryTemplate = properties.retryTemplate("test")
            var attemptCount = 0
            assertThrows<MismatchedInputException> {
                retryTemplate.execute<Unit, Exception> {
                    attemptCount++
                    throw MismatchedInputException.from(null, String::class.java, "missing field")
                }
            }
            assertEquals(3, attemptCount, "MismatchedInputException is retryable — LLM may include field on next attempt")
        }

        @Test
        fun `should log how to configure maximum attempt`(output: CapturedOutput) {
            val properties = LlmDataBindingProperties(maxAttempts = 1)
            val retryTemplate = properties.retryTemplate("test")
            var attemptCount = 0
            assertThrows<RuntimeException> {
                retryTemplate.execute<Unit, RuntimeException> {
                    attemptCount++
                    throw RuntimeException("Dummy error")
                }
            }
            // Should only attempt once
            assertEquals(1, attemptCount)
            assertTrue(output.out.contains("Maximum attempts of 1 have been reached. The maximum attempt can be configured using property embabel.agent.platform.llm-operations.data-binding.max-attempts"))
        }
    }

    @Nested
    inner class RetryLoggingTest {

        /** Lines the retry listener rendered, so unrelated console output cannot mask a stray placeholder. */
        private fun retryLines(output: CapturedOutput): List<String> =
            output.out.lines().filter { it.contains("LLM invocation") }

        private fun failWith(error: Throwable) {
            val retryTemplate = LlmDataBindingProperties(maxAttempts = 2).retryTemplate("bind-1")
            assertThrows<RuntimeException> {
                retryTemplate.execute<Unit, RuntimeException> { throw error }
            }
        }

        @Test
        fun `rate limited retry is logged with every placeholder filled`(output: CapturedOutput) {
            failWith(RuntimeException("429 - Too many requests"))

            val lines = retryLines(output)
            assertTrue(lines.isNotEmpty(), "expected the listener to log")
            assertTrue(lines.none { it.contains("{}") }, "unfilled placeholder in $lines")
            assertTrue(lines.any { it.contains("LLM invocation bind-1 RATE LIMITED: Retry attempt 1 of 2") }, "$lines")
        }

        @Test
        fun `retry failure is logged with every placeholder filled`(output: CapturedOutput) {
            failWith(RuntimeException("connection reset"))

            val lines = retryLines(output)
            assertTrue(lines.isNotEmpty(), "expected the listener to log")
            assertTrue(lines.none { it.contains("{}") }, "unfilled placeholder in $lines")
            assertTrue(lines.any { it.contains("LLM invocation bind-1: Retry attempt 1 of 2 due to: connection reset") }, "$lines")
        }
    }
}
