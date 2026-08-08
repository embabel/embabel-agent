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

import com.embabel.agent.core.AgentProcess
import com.embabel.common.ai.model.ModelSelectionContext
import com.embabel.common.ai.model.ModelSelectionContextHolder
import com.embabel.common.ai.model.ProviderCredential
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The model selection context has to survive the platform moving work off the calling thread -
 * `AgentPlatform.start`, parallel actions, `OperationContext.parallelMap` all go through
 * [Asyncer].
 *
 * Losing it does not fail loudly: role resolution falls back to deployment configuration and
 * serves a model the deployment is keyed and billed for, on a call the user brought their own
 * key for. That is why this is pinned rather than left to the application.
 */
@Timeout(30)
class ModelSelectionContextPropagationTest {

    // Unbounded on purpose. parallelMap submits to this executor and then blocks on join(), so a
    // bounded pool deadlocks as soon as one parallelMap runs inside another: the outer tasks hold
    // every thread while waiting for inner tasks that can never be scheduled. Production wiring
    // uses a cached or virtual-thread executor for the same reason.
    private val executor = Executors.newCachedThreadPool()
    private val asyncer = ExecutorAsyncer(executor)

    @AfterEach
    fun cleanup() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }

    @Test
    fun `the user's credential reaches the worker thread`() {
        val context = ModelSelectionContext("ben", ProviderCredential("anthropic", "sk-test"))

        val seen = ModelSelectionContextHolder.with(context) {
            asyncer.async { ModelSelectionContextHolder.get() }
        }.get(5, TimeUnit.SECONDS)

        assertEquals(context, seen)
    }

    @Test
    fun `parallelMap carries the context to every worker`() {
        val context = ModelSelectionContext("ben", ProviderCredential("anthropic", "sk-test"))

        val seen = ModelSelectionContextHolder.with(context) {
            asyncer.parallelMap(listOf(1, 2, 3, 4), maxConcurrency = 2) {
                ModelSelectionContextHolder.get()
            }
        }

        assertEquals(List(4) { context }, seen)
    }

    @Test
    fun `the model selection context and the AgentProcess propagate together`() {
        // These are two separate ThreadLocals restored by the same wrapper. A change that
        // established one inside the other's try/finally could drop the outer on the way out,
        // and nothing else in the suite would notice.
        val process = mockk<AgentProcess>(relaxed = true)
        val context = ModelSelectionContext("ben", ProviderCredential("anthropic", "sk-test"))
        AgentProcess.set(process)
        try {
            val seen = ModelSelectionContextHolder.with(context) {
                asyncer.async { AgentProcess.get() to ModelSelectionContextHolder.get() }
            }.get(5, TimeUnit.SECONDS)

            assertSame(process, seen.first, "AgentProcess must still propagate")
            assertEquals(context, seen.second, "model selection context must propagate alongside it")
        } finally {
            AgentProcess.remove()
        }
        assertEquals(ModelSelectionContext.EMPTY, ModelSelectionContextHolder.get())
    }

    @Test
    fun `nested parallelMap keeps the context on every level`() {
        // The parallel tool loop fans out from work that is itself already on a worker thread.
        // Propagation that only survives one hop would look fine in a single-level test.
        //
        // Needs the unbounded executor above: nesting parallelMap on a bounded pool deadlocks.
        val context = ModelSelectionContext("ben", ProviderCredential("anthropic", "sk-test"))

        val seen = ModelSelectionContextHolder.with(context) {
            asyncer.parallelMap(listOf(1, 2), maxConcurrency = 2) {
                asyncer.parallelMap(listOf(1, 2), maxConcurrency = 2) {
                    ModelSelectionContextHolder.get()
                }
            }
        }

        assertEquals(List(2) { List(2) { context } }, seen)
    }

    @Test
    fun `a task leaves no context behind for the next task on the same thread`() {
        val single = Executors.newSingleThreadExecutor()
        try {
            val singleAsyncer = ExecutorAsyncer(single)
            ModelSelectionContextHolder.with(ModelSelectionContext("ben", ProviderCredential("anthropic", "sk-test"))) {
                singleAsyncer.async { ModelSelectionContextHolder.get() }
            }.get(5, TimeUnit.SECONDS)

            // Same pooled thread, no context on the caller this time: it must not inherit ben's key
            val seen = singleAsyncer.async { ModelSelectionContextHolder.get() }.get(5, TimeUnit.SECONDS)
            assertEquals(ModelSelectionContext.EMPTY, seen)
        } finally {
            single.shutdown()
            single.awaitTermination(5, TimeUnit.SECONDS)
        }
    }
}
