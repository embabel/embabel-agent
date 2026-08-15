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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.internal.streaming.StreamingLlmOperationsFactory
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.LlmOptions
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import reactor.core.publisher.Flux
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [StreamingCapabilityDetector].
 */
class StreamingCapabilityDetectorTest {

    @AfterEach
    fun clearCache() {
        StreamingCapabilityDetector.clearCache()
    }

    @Nested
    inner class PromptRunnerPath {

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

            assertFalse(StreamingCapabilityDetector.supportsStreaming(mockFactory, options))
            assertFalse(StreamingCapabilityDetector.supportsStreaming(mockFactory, options))

            verify(exactly = 2) { mockFactory.supportsStreaming(options) }
        }

        @Test
        fun `supportsStreaming caches result for same model`() {
            val mockFactory = mockk<TestStreamingLlmOperationsFactory>()
            val options = LlmOptions.withModel("cached-model")

            every { mockFactory.supportsStreaming(options) } returns true

            StreamingCapabilityDetector.supportsStreaming(mockFactory, options)
            StreamingCapabilityDetector.supportsStreaming(mockFactory, options)

            verify(exactly = 1) { mockFactory.supportsStreaming(options) }
        }

        @Test
        fun `supportsStreaming caches by criteria when model name is absent`() {
            val mockFactory = mockk<TestStreamingLlmOperationsFactory>()
            val options = LlmOptions.withAutoLlm()

            every { mockFactory.supportsStreaming(options) } returns true

            StreamingCapabilityDetector.supportsStreaming(mockFactory, options)
            StreamingCapabilityDetector.supportsStreaming(mockFactory, options)

            verify(exactly = 1) { mockFactory.supportsStreaming(options) }
        }

        /**
         * computeIfAbsent would have stored the first false under the model name and left
         * PromptRunner non-streaming after a blip. The ChatModel cache must stay the source
         * of truth for a no.
         */
        @Test
        fun `a transient no on the PromptRunner path is not stored under the model name`() {
            val failuresLeft = java.util.concurrent.atomic.AtomicInteger(1)
            val chatModel = CountingChatModel {
                if (failuresLeft.getAndDecrement() > 0) {
                    throw RuntimeException("provider unreachable")
                }
                Flux.just(chatResponse("ok"))
            }
            val options = LlmOptions.withModel("recovering-prompt-runner-model")
            val factory = mockk<TestStreamingLlmOperationsFactory>()
            every { factory.supportsStreaming(options) } answers {
                StreamingCapabilityDetector.supportsStreaming(chatModel)
            }

            assertFalse(StreamingCapabilityDetector.supportsStreaming(factory, options))
            assertTrue(StreamingCapabilityDetector.supportsStreaming(factory, options))
            assertTrue(StreamingCapabilityDetector.supportsStreaming(factory, options))

            assertEquals(2, chatModel.streamCalls.get())
            verify(exactly = 2) { factory.supportsStreaming(options) }
        }
    }

    @Nested
    inner class ChatModelPath {

        @Test
        fun `probes once when streaming is supported`() {
            val chatModel = CountingChatModel { Flux.just(chatResponse("ok")) }

            assertTrue(StreamingCapabilityDetector.supportsStreaming(chatModel))
            assertTrue(StreamingCapabilityDetector.supportsStreaming(chatModel))

            assertEquals(1, chatModel.streamCalls.get())
        }

        @Test
        fun `probes once when stream throws UnsupportedOperationException`() {
            val chatModel = CountingChatModel {
                throw UnsupportedOperationException("streaming not supported")
            }

            assertFalse(StreamingCapabilityDetector.supportsStreaming(chatModel))
            assertFalse(StreamingCapabilityDetector.supportsStreaming(chatModel))

            assertEquals(1, chatModel.streamCalls.get())
        }

        @Test
        fun `caches subclasses of UnsupportedOperationException`() {
            val chatModel = CountingChatModel {
                throw object : UnsupportedOperationException("streaming not supported") {}
            }

            assertFalse(StreamingCapabilityDetector.supportsStreaming(chatModel))
            assertFalse(StreamingCapabilityDetector.supportsStreaming(chatModel))

            assertEquals(1, chatModel.streamCalls.get())
        }

        @Test
        fun `non-capability failures return false and are not cached`() {
            val chatModel = CountingChatModel {
                throw RuntimeException("No LLM is configured")
            }

            assertFalse(StreamingCapabilityDetector.supportsStreaming(chatModel))
            assertFalse(StreamingCapabilityDetector.supportsStreaming(chatModel))

            assertEquals(2, chatModel.streamCalls.get())
        }

        /**
         * The caller only learns "no streaming", so without this the reason a provider outage
         * changed the execution path would never surface anywhere.
         */
        @Test
        fun `a non-capability failure names the model and keeps the cause`() {
            val logger = LoggerFactory.getLogger(StreamingCapabilityDetector::class.java) as Logger
            val originalLevel = logger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.level = Level.WARN
            logger.addAppender(appender)
            val chatModel = CountingChatModel { throw RuntimeException("provider unreachable") }

            try {
                assertFalse(StreamingCapabilityDetector.supportsStreaming(chatModel))
                assertFalse(StreamingCapabilityDetector.supportsStreaming(chatModel))

                val warnings = appender.list.filter { it.level == Level.WARN }
                assertEquals(1, warnings.size, "Expected one warning, captured: ${appender.list.map { it.formattedMessage }}")
                val event = warnings.single()
                assertNotNull(event)
                assertEquals(CountingChatModel::class.simpleName, event.argumentArray[0])
                assertEquals("provider unreachable", event.throwableProxy?.message)
                assertEquals(2, chatModel.streamCalls.get())
            } finally {
                logger.detachAppender(appender)
                logger.level = originalLevel
                appender.stop()
            }
        }

        @Test
        fun `a later successful probe is cached after a transient failure`() {
            val failuresLeft = java.util.concurrent.atomic.AtomicInteger(1)
            val chatModel = CountingChatModel {
                if (failuresLeft.getAndDecrement() > 0) {
                    throw RuntimeException("provider unreachable")
                }
                Flux.just(chatResponse("ok"))
            }

            assertFalse(StreamingCapabilityDetector.supportsStreaming(chatModel))
            assertTrue(StreamingCapabilityDetector.supportsStreaming(chatModel))
            assertTrue(StreamingCapabilityDetector.supportsStreaming(chatModel))

            assertEquals(2, chatModel.streamCalls.get())
        }

        @Test
        fun `distinct ChatModel instances are probed separately`() {
            val first = CountingChatModel { Flux.just(chatResponse("a")) }
            val second = CountingChatModel { Flux.just(chatResponse("b")) }

            assertTrue(StreamingCapabilityDetector.supportsStreaming(first))
            assertTrue(StreamingCapabilityDetector.supportsStreaming(second))

            assertEquals(1, first.streamCalls.get())
            assertEquals(1, second.streamCalls.get())
        }

        @Test
        fun `Spring AI default stream is unsupported and cached`() {
            val chatModel = DefaultStreamChatModel()

            assertFalse(StreamingCapabilityDetector.supportsStreaming(chatModel))
            assertFalse(StreamingCapabilityDetector.supportsStreaming(chatModel))
        }

        @Test
        fun `empty flux is treated as streaming-capable and cached`() {
            val chatModel = CountingChatModel { Flux.empty() }

            assertTrue(StreamingCapabilityDetector.supportsStreaming(chatModel))
            assertTrue(StreamingCapabilityDetector.supportsStreaming(chatModel))

            assertEquals(1, chatModel.streamCalls.get())
        }

        @Test
        fun `a stream that never emits is treated as streaming-capable and cached`() {
            val chatModel = CountingChatModel { Flux.never() }

            assertTrue(StreamingCapabilityDetector.supportsStreaming(chatModel))
            assertTrue(StreamingCapabilityDetector.supportsStreaming(chatModel))

            assertEquals(1, chatModel.streamCalls.get())
        }
    }

    @Nested
    inner class SharedCache {

        @Test
        fun `PromptRunner path and LlmService path share one ChatModel probe`() {
            val chatModel = CountingChatModel { Flux.just(chatResponse("ok")) }
            val options = LlmOptions.withModel("shared-probe-model")
            val factory = mockk<TestStreamingLlmOperationsFactory>()
            every { factory.supportsStreaming(options) } answers {
                StreamingCapabilityDetector.supportsStreaming(chatModel)
            }

            assertTrue(StreamingCapabilityDetector.supportsStreaming(factory, options))
            assertTrue(StreamingCapabilityDetector.supportsStreaming(chatModel))
            assertTrue(
                SpringAiLlmService(name = "shared", provider = "test", chatModel = chatModel)
                    .supportsStreaming()
            )

            assertEquals(1, chatModel.streamCalls.get())
            verify(exactly = 1) { factory.supportsStreaming(options) }
        }

        @Test
        fun `LlmService probe is reused when PromptRunner later asks about the same ChatModel`() {
            val chatModel = CountingChatModel { Flux.just(chatResponse("ok")) }
            val options = LlmOptions.withModel("llm-first-model")
            val factory = mockk<TestStreamingLlmOperationsFactory>()
            every { factory.supportsStreaming(options) } answers {
                StreamingCapabilityDetector.supportsStreaming(chatModel)
            }

            assertTrue(
                SpringAiLlmService(name = "first", provider = "test", chatModel = chatModel)
                    .supportsStreaming()
            )
            assertTrue(StreamingCapabilityDetector.supportsStreaming(factory, options))

            assertEquals(1, chatModel.streamCalls.get())
        }

        @Test
        fun `SpringAiLlmService copies that wrap the same ChatModel share the cache`() {
            val chatModel = CountingChatModel { Flux.just(chatResponse("ok")) }
            val original = SpringAiLlmService(name = "original", provider = "test", chatModel = chatModel)
            val copy = original.copy(name = "copy")

            assertTrue(original.supportsStreaming())
            assertTrue(copy.supportsStreaming())

            assertEquals(1, chatModel.streamCalls.get())
        }

        @Test
        fun `concurrent first probes agree and later calls do not probe again`() {
            val chatModel = CountingChatModel { Flux.just(chatResponse("ok")) }
            val service = SpringAiLlmService(name = "concurrent", provider = "test", chatModel = chatModel)
            val threads = 16
            val start = CountDownLatch(1)
            val done = CountDownLatch(threads)
            val results = java.util.concurrent.ConcurrentLinkedQueue<Boolean>()
            val pool = Executors.newFixedThreadPool(threads)
            try {
                repeat(threads) {
                    pool.execute {
                        start.await()
                        results.add(service.supportsStreaming())
                        done.countDown()
                    }
                }
                start.countDown()
                assertTrue(done.await(5, TimeUnit.SECONDS))
            } finally {
                pool.shutdownNow()
            }

            assertEquals(threads, results.size)
            assertTrue(results.all { it })
            val probesDuringRace = chatModel.streamCalls.get()
            assertTrue(probesDuringRace >= 1)
            assertTrue(service.supportsStreaming())
            assertEquals(probesDuringRace, chatModel.streamCalls.get())
        }
    }

    /**
     * Test interface that combines LlmOperations and StreamingLlmOperationsFactory
     * for mocking purposes.
     */
    private interface TestStreamingLlmOperationsFactory : LlmOperations, StreamingLlmOperationsFactory
}
