/*
 * Copyright 2024-2025 Embabel Software, Inc.
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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.Semaphore

class ExecutorAsyncer(
    private val executor: Executor,
) : Asyncer {

    override fun <T> async(block: () -> T): CompletableFuture<T> {
        return CompletableFuture.supplyAsync(block, executor)
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
