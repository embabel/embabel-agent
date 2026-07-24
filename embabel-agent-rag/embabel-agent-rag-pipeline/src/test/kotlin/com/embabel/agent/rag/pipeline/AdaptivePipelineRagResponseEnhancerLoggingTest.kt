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
package com.embabel.agent.rag.pipeline

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.event.RagEventListener
import com.embabel.agent.rag.service.DesiredMaxLatency
import com.embabel.agent.rag.service.RagRequest
import com.embabel.agent.rag.service.RagResponse
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

class AdaptivePipelineRagResponseEnhancerLoggingTest {

    @Test
    fun `latency skip log identifies enhancer and reports numeric milliseconds`() {
        val logger = LoggerFactory.getLogger(AdaptivePipelineRagResponseEnhancer::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)

        try {
            val request = RagRequest("q").withHint(
                DesiredMaxLatency((-1).milliseconds.toJavaDuration()),
            )
            val response = RagResponse(request = request, service = "test", results = emptyList())
            val pipeline = AdaptivePipelineRagResponseEnhancer(
                enhancers = listOf(DeduplicatingEnhancer),
                listener = RagEventListener {},
            )

            pipeline.enhance(response)

            val event = appender.list.single {
                it.message.startsWith("Skipping enhancer")
            }
            assertEquals(DeduplicatingEnhancer.name, event.argumentArray[0])
            assertTrue(event.argumentArray[1] is Long)
            assertEquals(-1L, event.argumentArray[2])
            assertTrue(event.formattedMessage.endsWith("ms with latency limit of -1ms"))
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }
}
