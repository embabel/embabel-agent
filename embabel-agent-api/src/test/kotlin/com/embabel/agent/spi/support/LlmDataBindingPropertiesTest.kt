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
            exception.blackboardUpdater(mockBlackboard)
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
            exception.blackboardUpdater(mockBlackboard)
            verify { mockBlackboard["intent"] = "support" }
            verify { mockBlackboard["confidence"] = 0.95 }
            verify { mockBlackboard["target"] = "handleSupport" }
        }
    }
}
