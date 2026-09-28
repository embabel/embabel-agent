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
package com.embabel.agent.rag.store

import com.embabel.agent.rag.model.Retrievable
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.util.VisualizableTask
import org.slf4j.Logger

/**
 * Utility for generating embeddings in configurable batches.
 *
 * Batch processing reduces API calls and improves throughput.
 * A failed batch doesn't stop the other batches. It is retried as two halves, down to single
 * chunks, so a rate limit, a timeout or one oversized chunk costs as little as one chunk.
 * Chunks that still fail are reported in [EmbeddingBatchResult.missingChunkIds].
 *
 * If calls keep failing with no success in between, for longer than it takes to isolate one
 * failing chunk and a failing neighbour, the service is treated as unavailable: no more calls
 * are made and every remaining chunk is reported missing.
 */
object EmbeddingBatchGenerator {

    /**
     * Embed [retrievables] in batches of [batchSize], retrying failed batches in halves.
     *
     * The returned result is the report of record for chunks that could not be embedded, and so is
     * the [EmbeddingIncompleteException] a repository throws from it. This method logs only a
     * warning summary, with no stack trace.
     */
    fun embedInBatches(
        embeddingService: EmbeddingService,
        retrievables: List<Retrievable>,
        batchSize: Int,
        logger: Logger,
    ): EmbeddingBatchResult {
        if (retrievables.isEmpty()) {
            return EmbeddingBatchResult(emptyMap(), emptyList(), null)
        }

        val batches = retrievables.chunked(batchSize)
        val run = EmbeddingBatchRun(embeddingService, logger, abandonAfter = halvingsToOne(batchSize) + 3)

        fun logProgress(current: Int) {
            val progress = VisualizableTask(
                name = "Generating embeddings",
                current = current,
                total = batches.size
            )
            logger.info(progress.createProgressBar())
        }

        logProgress(0)
        batches.forEachIndexed { index, batch ->
            run.embed(batch)
            logProgress(index + 1)
        }

        val result = run.result()
        if (!result.isComplete) {
            // A warning with the message only: the result carries the cause, and a caller that throws
            // EmbeddingIncompleteException reports it, so an error with a stack trace here would repeat it.
            logger.warn(
                "{} of {} chunks could not be embedded ({}): {}",
                result.missingChunkIds.size,
                retrievables.size,
                describeFailure(result.cause),
                describeIds(result.missingChunkIds),
            )
        }
        return result
    }

    /**
     * Embed in batches, returning only the embeddings that succeeded.
     *
     * Chunks that could not be embedded are absent from the map. Their ids are logged at WARN, but
     * the caller is not told, so use [embedInBatches] and check [EmbeddingBatchResult.missingChunkIds].
     */
    @Deprecated(
        message = "Chunks that could not be embedded are only logged at WARN, not reported to the caller",
        replaceWith = ReplaceWith("embedInBatches(embeddingService, retrievables, batchSize, logger).embeddings"),
    )
    fun generateEmbeddingsInBatches(
        embeddingService: EmbeddingService,
        retrievables: List<Retrievable>,
        batchSize: Int,
        logger: Logger,
    ): Map<String, FloatArray> = embedInBatches(embeddingService, retrievables, batchSize, logger).embeddings

    /**
     * How many times [size] halves, rounding up, before it reaches 1.
     *
     * For a batch of 100 that is 7: 100, 50, 25, 13, 7, 4, 2, 1. One bad chunk in that batch fails
     * every call on its path, 8 in a row, before it is isolated; the other half at each level
     * succeeds and resets the count. The failure limit is set a little above that, so isolating a
     * bad chunk never trips it, but a service that fails everything does within about 10 calls.
     */
    private fun halvingsToOne(size: Int): Int =
        // The sequence is size and each halving after it, ending at 1: for 3 it is 3, 2, 1. count()
        // includes size itself, so the number of halvings is one less: 2 for 3, 7 for 100.
        generateSequence(size) { if (it > 1) (it + 1) / 2 else null }.count() - 1

    private fun describeIds(ids: List<String>): String =
        if (ids.size <= MAX_IDS_LOGGED) ids.joinToString()
        else "${ids.take(MAX_IDS_LOGGED).joinToString()} and ${ids.size - MAX_IDS_LOGGED} more"

    private const val MAX_IDS_LOGGED = 20
}
