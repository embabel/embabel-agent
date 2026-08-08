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

import com.embabel.common.ai.model.ModelSelectionContext
import com.embabel.common.ai.model.ModelSelectionContextHolder
import com.embabel.common.ai.model.ProviderCredential
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
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
class ModelSelectionContextPropagationTest {

    private val executor = Executors.newFixedThreadPool(2)
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
