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
@file:OptIn(InternalStreamingApi::class)

package com.embabel.agent.spi.support.streaming

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.core.internal.LlmOperations
import com.embabel.agent.core.internal.streaming.StreamingLlmOperationsFactory
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ModelSelectionCriteria
import com.embabel.common.ai.model.PreResolvedModelSelectionCriteria
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

        /**
         * Only a model name is a safe key. Each of these resolves against whatever is registered,
         * or against a resolver the application supplies, so a yes stored under the criteria
         * string would answer for a model that was never probed.
         */
        @Test
        fun `criteria that do not name one model are not cached`() {
            val mustNotCache = listOf(
                LlmOptions.withAutoLlm(),
                LlmOptions.withDefaultLlm(),
                LlmOptions.withFirstAvailableLlmOf("primary", "secondary"),
                LlmOptions(modelSelectionCriteria = ModelSelectionCriteria.randomOf("first", "second")),
                LlmOptions.withLlmForRole("cheapest"),
            )

            mustNotCache.forEach { options ->
                val factory = mockk<TestStreamingLlmOperationsFactory>()
                every { factory.supportsStreaming(options) } returns true

                assertTrue(StreamingCapabilityDetector.supportsStreaming(factory, options))
                assertTrue(StreamingCapabilityDetector.supportsStreaming(factory, options))

                verify(exactly = 2) { factory.supportsStreaming(options) }
            }
        }

        /**
         * randomOf() resolves with models.random(), so one key covers every name in the list.
         * A yes cached from the streaming pick would send the non-streaming pick down the
         * streaming path without ever probing it. No BYOK needed to reach this.
         */
        @Test
        fun `a random criteria resolving to a second model does not reuse the first answer`() {
            val streams = CountingChatModel { Flux.just(chatResponse("ok")) }
            val doesNotStream = CountingChatModel {
                throw UnsupportedOperationException("streaming not supported")
            }
            val options = LlmOptions(
                modelSelectionCriteria = ModelSelectionCriteria.randomOf("streams", "does-not"),
            )
            val picks = listOf(streams, doesNotStream).iterator()
            val factory = mockk<TestStreamingLlmOperationsFactory>()
            every { factory.supportsStreaming(options) } answers {
                StreamingCapabilityDetector.supportsStreaming(picks.next())
            }

            assertTrue(StreamingCapabilityDetector.supportsStreaming(factory, options))
            assertFalse(StreamingCapabilityDetector.supportsStreaming(factory, options))

            assertEquals(1, streams.streamCalls.get())
            assertEquals(1, doesNotStream.streamCalls.get())
        }

        /**
         * A role is keyed here before withRoleResolved() runs, and resolution reads the ambient
         * selection context and can land on a per-credential service. So the same role means
         * Alice's model on one call and Bob's on the next.
         */
        @Test
        fun `a role resolving per credential does not reuse the first answer`() {
            val aliceModel = CountingChatModel { Flux.just(chatResponse("ok")) }
            val bobModel = CountingChatModel {
                throw UnsupportedOperationException("streaming not supported")
            }
            val options = LlmOptions.withLlmForRole("cheapest")
            val perCredential = listOf(aliceModel, bobModel).iterator()
            val factory = mockk<TestStreamingLlmOperationsFactory>()
            every { factory.supportsStreaming(options) } answers {
                StreamingCapabilityDetector.supportsStreaming(perCredential.next())
            }

            assertTrue(StreamingCapabilityDetector.supportsStreaming(factory, options))
            assertFalse(StreamingCapabilityDetector.supportsStreaming(factory, options))

            assertEquals(1, aliceModel.streamCalls.get())
            assertEquals(1, bobModel.streamCalls.get())
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

        /**
         * withLlmService() keys every runner as PreResolvedModelSelectionCriteria(SpringAiLlmService).
         * Caching a yes under that string would make Bob's non-streaming model take the streaming path.
         */
        @Test
        fun `pre-resolved services do not share a model name cache entry`() {
            val aliceModel = CountingChatModel { Flux.just(chatResponse("ok")) }
            val bobModel = CountingChatModel {
                throw UnsupportedOperationException("streaming not supported")
            }
            val aliceOptions = preResolvedOptions("alice", aliceModel)
            val bobOptions = preResolvedOptions("bob", bobModel)
            val factory = mockk<TestStreamingLlmOperationsFactory>()
            every { factory.supportsStreaming(aliceOptions) } answers {
                StreamingCapabilityDetector.supportsStreaming(aliceModel)
            }
            every { factory.supportsStreaming(bobOptions) } answers {
                StreamingCapabilityDetector.supportsStreaming(bobModel)
            }

            assertEquals(aliceOptions.criteria.toString(), bobOptions.criteria.toString())

            assertTrue(StreamingCapabilityDetector.supportsStreaming(factory, aliceOptions))
            assertFalse(StreamingCapabilityDetector.supportsStreaming(factory, bobOptions))

            verify(exactly = 1) { factory.supportsStreaming(aliceOptions) }
            verify(exactly = 1) { factory.supportsStreaming(bobOptions) }
            assertEquals(1, aliceModel.streamCalls.get())
            assertEquals(1, bobModel.streamCalls.get())
        }

        @Test
        fun `a pre-resolved service still reuses the ChatModel identity cache`() {
            val chatModel = CountingChatModel { Flux.just(chatResponse("ok")) }
            val options = preResolvedOptions("alice", chatModel)
            val factory = mockk<TestStreamingLlmOperationsFactory>()
            every { factory.supportsStreaming(options) } answers {
                StreamingCapabilityDetector.supportsStreaming(chatModel)
            }

            assertTrue(StreamingCapabilityDetector.supportsStreaming(factory, options))
            assertTrue(StreamingCapabilityDetector.supportsStreaming(factory, options))

            verify(exactly = 2) { factory.supportsStreaming(options) }
            assertEquals(1, chatModel.streamCalls.get())
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
                assertEquals(CountingChatModel::class.simpleName, event.argumentArray[0])
                assertEquals("RuntimeException", event.argumentArray[1])
                assertEquals("provider unreachable", event.argumentArray[2])
                assertEquals(null, event.throwableProxy, "the warning must not attach a stack")
                assertEquals(2, chatModel.streamCalls.get())
            } finally {
                logger.detachAppender(appender)
                logger.level = originalLevel
                appender.stop()
            }
        }

        @Test
        fun `a missing key and a network failure are named differently in the warning`() {
            val logger = LoggerFactory.getLogger(StreamingCapabilityDetector::class.java) as Logger
            val originalLevel = logger.level
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.level = Level.WARN
            logger.addAppender(appender)

            try {
                assertFalse(
                    StreamingCapabilityDetector.supportsStreaming(
                        CountingChatModel { throw RuntimeException("No LLM is configured") },
                    )
                )
                assertFalse(
                    StreamingCapabilityDetector.supportsStreaming(
                        CountingChatModel { throw java.io.IOException("connection timed out") },
                    )
                )

                val warnings = appender.list.filter { it.level == Level.WARN }
                assertEquals(2, warnings.size, "Expected two warnings, captured: ${appender.list.map { it.formattedMessage }}")
                assertEquals("RuntimeException", warnings[0].argumentArray[1])
                assertEquals("No LLM is configured", warnings[0].argumentArray[2])
                assertEquals("IOException", warnings[1].argumentArray[1])
                assertEquals("connection timed out", warnings[1].argumentArray[2])
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

        /**
         * Guards the identity keying. Two instances that report themselves equal must still be
         * probed separately, or a later switch to an equals-based map would silently give the
         * second one an answer that was never measured on it.
         */
        @Test
        fun `ChatModels that call themselves equal are still probed separately`() {
            val streams = EqualsByNameChatModel("gpt-4o") { Flux.just(chatResponse("ok")) }
            val doesNotStream = EqualsByNameChatModel("gpt-4o") {
                throw UnsupportedOperationException("streaming not supported")
            }

            assertEquals(streams, doesNotStream)

            assertTrue(StreamingCapabilityDetector.supportsStreaming(streams))
            assertFalse(StreamingCapabilityDetector.supportsStreaming(doesNotStream))

            assertEquals(1, streams.streamCalls.get())
            assertEquals(1, doesNotStream.streamCalls.get())
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

    private fun preResolvedOptions(name: String, chatModel: CountingChatModel): LlmOptions =
        LlmOptions(
            modelSelectionCriteria = PreResolvedModelSelectionCriteria(
                SpringAiLlmService(name = name, provider = "test", chatModel = chatModel),
            ),
        )

    /**
     * Test interface that combines LlmOperations and StreamingLlmOperationsFactory
     * for mocking purposes.
     */
    private interface TestStreamingLlmOperationsFactory : LlmOperations, StreamingLlmOperationsFactory
}
