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
package com.embabel.agent.api.dsl

import com.embabel.agent.api.common.OperationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Map parallel. Block on all results
 * @param T the type of elements in the collection
 * @param R the type of the result of the transformation
 * @param context the operation context
 * @param concurrencyLevel the maximum number of concurrent operations
 * @param dispatcher the coroutine dispatcher to use for parallel execution
 * @param transform the transformation function to apply to each element
 */
fun <T, R> Collection<T>.parallelMap(
    context: OperationContext,
    concurrencyLevel: Int = 10,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    transform: suspend (T) -> R,
): List<R> =
    runBlocking {
        mapAsync(
            context = context,
            concurrencyLevel = concurrencyLevel,
            dispatcher = dispatcher,
            transform = transform,
        )
    }


/**
 * Map async, using the agent process
 */
suspend fun <T, R> Collection<T>.mapAsync(
    context: OperationContext,
    concurrencyLevel: Int = 10,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    transform: suspend (T) -> R
): List<R> = coroutineScope {
    if (isEmpty()) return@coroutineScope emptyList()

    // Use kotlinx.coroutines.sync.Semaphore for proper concurrency control
    val semaphore = Semaphore(concurrencyLevel)

    map { item ->
        async(dispatcher) {
            semaphore.withPermit {
                transform(item)
            }
        }
    }.awaitAll()
}
