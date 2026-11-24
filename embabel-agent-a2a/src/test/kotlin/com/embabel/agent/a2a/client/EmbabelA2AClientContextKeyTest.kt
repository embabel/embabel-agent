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
package com.embabel.agent.a2a.client

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger as LogbackLogger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.a2a.A2A_CONTEXT_ID_KEY
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.opentelemetry.context.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.slf4j.LoggerFactory

@ExtendWith(MockitoExtension::class)
class EmbabelA2AClientContextKeyTest {

    @Mock
    private lateinit var httpClientFactory: A2AHttpClientFactory

    private val client by lazy {
        EmbabelA2AClient(httpClientFactory, EmbabelObjectMapperHolder.createDefault())
    }

    @Test
    fun `resolveContextId prefers the agent's own message contextId over the inherited one`() {
        val scope = Context.current().with(A2A_CONTEXT_ID_KEY, "upstream-ctx").makeCurrent()
        scope.use {
            assertEquals(
                "agent-ctx",
                client.resolveContextId("agent-ctx"),
                "A contextId the agent sets itself must not be replaced by the inherited one",
            )
        }
    }

    @Test
    fun `resolveContextId uses the inherited contextId when the message has none`() {
        val scope = Context.current().with(A2A_CONTEXT_ID_KEY, "upstream-ctx").makeCurrent()
        scope.use {
            assertEquals("upstream-ctx", client.resolveContextId(null))
        }
    }

    @Test
    fun `resolveContextId logs when the agent's contextId replaces the inherited one`() {
        val logs = captureClientLogs {
            Context.current().with(A2A_CONTEXT_ID_KEY, "upstream-ctx").makeCurrent().use {
                client.resolveContextId("agent-ctx")
            }
        }
        assertEquals(1, logs.size)
        assertEquals(Level.INFO, logs.single().level)
        assertTrue(logs.single().formattedMessage.contains("agent-ctx"))
        assertTrue(logs.single().formattedMessage.contains("upstream-ctx"))
    }

    @Test
    fun `resolveContextId does not log when the agent's contextId equals the inherited one`() {
        val logs = captureClientLogs {
            Context.current().with(A2A_CONTEXT_ID_KEY, "same-ctx").makeCurrent().use {
                client.resolveContextId("same-ctx")
            }
        }
        assertTrue(logs.isEmpty())
    }

    private fun captureClientLogs(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(EmbabelA2AClient::class.java) as LogbackLogger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            block()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.formattedMessage.contains("replaces inbound contextId") }
    }

    @Test
    fun `resolveContextId falls back to message contextId when context key is absent`() {
        // No A2A_CONTEXT_ID_KEY set — bare context
        assertEquals(
            "msg-ctx-fallback",
            client.resolveContextId("msg-ctx-fallback"),
            "Should fall back to message contextId when context key is absent",
        )
    }

    @Test
    fun `resolveContextId returns null when context key absent and message contextId null`() {
        assertNull(client.resolveContextId(null))
    }
}
