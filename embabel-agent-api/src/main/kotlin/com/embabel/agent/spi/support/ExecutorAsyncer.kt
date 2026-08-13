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

import com.embabel.agent.api.common.Asyncer
import com.embabel.common.ai.model.ModelSelectionContextHolder
import io.micrometer.context.ContextSnapshotFactory
import javax.annotation.concurrent.ThreadSafe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore

/**
 * Asyncer implementation that uses an Executor for async operations, propagating to worker
 * threads the [AgentProcess] (a domain concern, via [AgentProcessAccessor]), the
 * [com.embabel.common.ai.model.ModelSelectionContext] (so a user's own provider key still
 * decides model selection off the request thread), and the current Micrometer Observation
 * (via the official [ContextSnapshotFactory], so spans nest across threads; a no-op when no
 * observation is current, e.g. a NOOP registry).
 *
 * The model selection context matters here because the platform itself moves work off the
 * calling thread - `AgentPlatform.start`, parallel actions, `OperationContext.parallelMap`.
 * Losing it does not fail: role resolution quietly falls back to deployment configuration and
 * serves a model the deployment pays for, on a call the user brought their own key for.
 */
@ThreadSafe
class ExecutorAsyncer(
    private val executor: Executor,
) : Asyncer {

    private val contextSnapshotFactory = ContextSnapshotFactory.builder().clearMissing(true).build()

    override fun <T> async(block: () -> T): CompletableFuture<T> {
        // Capture AgentProcess, model selection context and the current observation from the calling thread
        val agentProcess = AgentProcessAccessor.getValue()
        val modelSelectionContext = ModelSelectionContextHolder.get()
        val contextSnapshot = contextSnapshotFactory.captureAll()

        return CompletableFuture.supplyAsync({
            contextSnapshot.setThreadLocals().use {
                // Lifecycle, in full. The application sets the context once at its request boundary
                // - a filter or interceptor - and it lives for that request. Nothing here creates
                // or owns it: this only carries the calling thread's value onto the worker and puts
                // the worker's own value back afterwards. A worker that arrived with nothing is
                // left with nothing, and one that was already inside a request keeps what it had,
                // which is what makes an executor that runs on the submitting thread safe. There is
                // no close or dispose step, and none is needed - the value is immutable, holds no
                // resources, and its lifetime ends with the request that set it.
                ModelSelectionContextHolder.with(modelSelectionContext) {
                    // Restores rather than clears: nothing leaks between tasks on a pooled thread,
                    // and nothing is wiped when the executor runs the task on the submitting
                    // thread, which is already inside a process. Same shape as the wrapper above,
                    // which is why they nest without either having to know about the other.
                    AgentProcessAccessor.with(agentProcess) {
                        block()
                    }
                }
            }
        }, executor)
    }

    override fun <T, R> parallelMap(
        items: Collection<T>,
        maxConcurrency: Int,
        transform: (t: T) -> R,
    ): List<R> {
        if (items.isEmpty()) {
            return mutableListOf()
        }

        if (maxConcurrency >= items.size) {
            // No concurrency limit needed - process all at once
            val futures = items.map { item ->
                async { transform(item) }
            }

            return futures.map { future ->
                future.join()
            }.toList()
        } else {
            // Use semaphore for concurrency control
            val semaphore = Semaphore(maxConcurrency)

            val futures = items.map { item ->
                async {
                    try {
                        semaphore.acquire()
                        try {
                            transform(item)
                        } finally {
                            semaphore.release()
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        throw RuntimeException("Interrupted while waiting for semaphore", e)
                    }
                }
            }

            return futures.map { future ->
                future.join()
            }.toList()
        }
    }

}
