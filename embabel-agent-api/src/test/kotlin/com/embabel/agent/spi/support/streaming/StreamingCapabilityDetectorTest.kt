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
package com.embabel.agent.spi.support.streaming

import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.internal.streaming.StreamingLlmOperations
import com.embabel.agent.core.internal.streaming.StreamingLlmOperationsFactory
import com.embabel.common.ai.model.LlmOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [StreamingCapabilityDetector].
 */
class StreamingCapabilityDetectorTest {

    @Test
    fun `supportsStreaming returns false when llmOperations is not StreamingLlmOperationsFactory`() {
        val mockLlmOperations = mockk<LlmOperations>()
        val options = LlmOptions.withModel("test-model")

        val result = StreamingCapabilityDetector.supportsStreaming(mockLlmOperations, options)

        assertFalse(result)
    }

    @Test
    fun `supportsStreaming delegates to factory when llmOperations is StreamingLlmOperationsFactory`() {
        val mockFactory = mockk<TestStreamingLlmOperationsFactory>()
        val options = LlmOptions.withModel("streaming-model")

        every { mockFactory.supportsStreaming(options) } returns true

        val result = StreamingCapabilityDetector.supportsStreaming(mockFactory, options)

        assertTrue(result)
        verify { mockFactory.supportsStreaming(options) }
    }

    @Test
    fun `supportsStreaming returns false when factory reports no streaming support`() {
        val mockFactory = mockk<TestStreamingLlmOperationsFactory>()
        val options = LlmOptions.withModel("non-streaming-model")

        every { mockFactory.supportsStreaming(options) } returns false

        val result = StreamingCapabilityDetector.supportsStreaming(mockFactory, options)

        assertFalse(result)
    }

    @Test
    fun `supportsStreaming caches result for same model`() {
        val mockFactory = mockk<TestStreamingLlmOperationsFactory>()
        val options = LlmOptions.withModel("cached-model")

        every { mockFactory.supportsStreaming(options) } returns true

        // Call twice
        StreamingCapabilityDetector.supportsStreaming(mockFactory, options)
        StreamingCapabilityDetector.supportsStreaming(mockFactory, options)

        // Factory should only be called once due to caching
        verify(exactly = 1) { mockFactory.supportsStreaming(options) }
    }

    /**
     * Test interface that combines LlmOperations and StreamingLlmOperationsFactory
     * for mocking purposes.
     */
    private interface TestStreamingLlmOperationsFactory : LlmOperations, StreamingLlmOperationsFactory
}
