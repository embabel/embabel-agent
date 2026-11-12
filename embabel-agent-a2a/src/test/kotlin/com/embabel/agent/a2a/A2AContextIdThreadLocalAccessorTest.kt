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
package com.embabel.agent.a2a

import com.embabel.agent.a2a.client.EmbabelA2AClient
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.common.util.EmbabelObjectMapperHolder
import io.micrometer.context.ContextRegistry
import io.mockk.mockk
import io.opentelemetry.context.Context
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

/**
 * The A2A contextId lives in the calling thread's OTel [Context]. Parallel actions and
 * `parallelMap` move work to other threads through [ExecutorAsyncer], which carries every
 * registered Micrometer [io.micrometer.context.ThreadLocalAccessor]. These tests check that
 * [A2AContextIdThreadLocalAccessor] is registered, so an A2A call made on a worker thread
 * still sends the inbound contextId, and that a pooled worker does not keep it afterwards.
 */
class A2AContextIdThreadLocalAccessorTest {

    // One worker thread, so a task and the next one are guaranteed to run on the same thread.
    private val executor = Executors.newSingleThreadExecutor()
    private val asyncer = ExecutorAsyncer(executor)
    private val a2aClient = EmbabelA2AClient(mockk(), EmbabelObjectMapperHolder.createDefault())

    @AfterEach
    fun shutdown() {
        executor.shutdownNow()
    }

    @Test
    fun `accessor is registered with the global ContextRegistry`() {
        assertTrue(
            ContextRegistry.getInstance().threadLocalAccessors.any { it is A2AContextIdThreadLocalAccessor },
            "A2AContextIdThreadLocalAccessor must be loaded via META-INF/services",
        )
    }

    @Test
    fun `A2A call on an asyncer worker thread resolves the caller's contextId`() {
        val resolved = Context.current().with(A2A_CONTEXT_ID_KEY, "ctx-parallel").makeCurrent().use {
            asyncer.async { a2aClient.resolveContextId(null) }.join()
        }

        assertEquals("ctx-parallel", resolved)
    }

    @Test
    fun `parallelMap workers all resolve the caller's contextId`() {
        val resolved = Context.current().with(A2A_CONTEXT_ID_KEY, "ctx-map").makeCurrent().use {
            asyncer.parallelMap(listOf(1, 2, 3), maxConcurrency = 3) { a2aClient.resolveContextId(null) }
        }

        assertEquals(listOf("ctx-map", "ctx-map", "ctx-map"), resolved)
    }

    @Test
    fun `worker thread does not keep the contextId after the task`() {
        Context.current().with(A2A_CONTEXT_ID_KEY, "ctx-first").makeCurrent().use {
            asyncer.async { a2aClient.resolveContextId(null) }.join()
        }

        // Same single worker thread, submitted without a contextId
        val leftover = asyncer.async { a2aClient.resolveContextId(null) }.join()

        assertNull(leftover)
    }
}
