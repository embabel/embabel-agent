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
package com.embabel.agent.spi.loop

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class EmptyResponsePolicyTest {

    @Nested
    inner class RetryWithFeedbackPolicyTest {

        @Test
        fun `returns feedback on first empty response`() {
            val policy = RetryWithFeedbackPolicy()
            val action = policy.handle()
            assertTrue(action is EmptyResponseAction.FeedbackToModel)
            val feedback = action as EmptyResponseAction.FeedbackToModel
            assertTrue(feedback.message.isNotBlank())
        }

        @Test
        fun `uses configured message`() {
            val policy = RetryWithFeedbackPolicy(message = "Try again, please.")
            val action = policy.handle() as EmptyResponseAction.FeedbackToModel
            assertEquals("Try again, please.", action.message)
        }

        @Test
        fun `throws after max retries exceeded`() {
            val policy = RetryWithFeedbackPolicy(maxRetries = 2)
            assertTrue(policy.handle() is EmptyResponseAction.FeedbackToModel)   // 1
            assertTrue(policy.handle() is EmptyResponseAction.FeedbackToModel)   // 2
            assertTrue(policy.handle() is EmptyResponseAction.Throw)             // 3 > maxRetries=2
        }

        @Test
        fun `counter resets on non-empty response`() {
            val policy = RetryWithFeedbackPolicy(maxRetries = 1)
            assertTrue(policy.handle() is EmptyResponseAction.FeedbackToModel)   // 1
            policy.onNonEmpty()
            assertTrue(policy.handle() is EmptyResponseAction.FeedbackToModel)   // 1 again, not exhausted
        }

        @Test
        fun `default max retries is 1`() {
            assertEquals(1, RetryWithFeedbackPolicy.DEFAULT_MAX_RETRIES)
        }

        @Test
        fun `zero max retries throws on first attempt`() {
            val policy = RetryWithFeedbackPolicy(maxRetries = 0)
            assertTrue(policy.handle() is EmptyResponseAction.Throw)
        }

        @Test
        fun `is thread-safe across concurrent handle calls`() {
            val policy = RetryWithFeedbackPolicy(maxRetries = 100)
            val threads = (1..10).map {
                Thread {
                    repeat(20) { policy.handle() }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            // After 200 calls (10 threads × 20 calls) and maxRetries=100, the
            // 101st handle would Throw; we just assert no IllegalState / race
            // by reaching Throw deterministically on the next call.
            val next = policy.handle()
            assertTrue(next is EmptyResponseAction.Throw)
        }
    }

    @Nested
    inner class ExitOnEmptyPolicyTest {

        @Test
        fun `returns Exit on every call`() {
            val policy = ExitOnEmptyPolicy
            assertEquals(EmptyResponseAction.Exit, policy.handle())
            assertEquals(EmptyResponseAction.Exit, policy.handle())
            assertEquals(EmptyResponseAction.Exit, policy.handle())
        }

        @Test
        fun `onNonEmpty is a no-op`() {
            val policy = ExitOnEmptyPolicy
            policy.onNonEmpty()
            assertEquals(EmptyResponseAction.Exit, policy.handle())
        }
    }
}
