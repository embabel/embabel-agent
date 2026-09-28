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
        val run = BatchRun(embeddingService, logger, abandonAfter = halvingsToOne(batchSize) + 3)

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
            // The message only, not the stack trace: the result carries the cause, and a caller that
            // throws EmbeddingIncompleteException passes it on, so logging it here would print it twice.
            logger.error(
                "{} of {} chunks could not be embedded ({}): {}",
                result.missingChunkIds.size,
                retrievables.size,
                describe(result.cause),
                describeIds(result.missingChunkIds),
            )
        }
        return result
    }

    /**
     * Embed in batches, returning only the embeddings that succeeded.
     */
    @Deprecated(
        message = "Chunks that could not be embedded are dropped without telling the caller",
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
        generateSequence(size) { if (it > 1) (it + 1) / 2 else null }.count() - 1

    private fun describe(e: Throwable?): String = e?.message ?: e?.javaClass?.simpleName ?: "unknown"

    private fun describeIds(ids: List<String>): String =
        if (ids.size <= MAX_IDS_LOGGED) ids.joinToString()
        else "${ids.take(MAX_IDS_LOGGED).joinToString()} and ${ids.size - MAX_IDS_LOGGED} more"

    private const val MAX_IDS_LOGGED = 20

    /**
     * State for one [embedInBatches] call.
     */
    private class BatchRun(
        private val embeddingService: EmbeddingService,
        private val logger: Logger,
        private val abandonAfter: Int,
    ) {
        private val embeddings = mutableMapOf<String, FloatArray>()
        private val missing = mutableListOf<String>()
        private var missingCause: Exception? = null
        private var consecutiveFailures = 0
        private var abandoned = false

        fun result() = EmbeddingBatchResult(
            embeddings = embeddings.toMap(),
            missingChunkIds = missing.toList(),
            cause = missingCause,
        )

        /**
         * Embed [batch], recording each chunk as embedded or missing.
         *
         * A failed batch is split in two and each half embedded in turn, down to single chunks.
         * A single chunk that fails is recorded missing. Once [abandonAfter] calls have failed in a
         * row, this and every later batch is recorded missing without calling the service.
         */
        fun embed(batch: List<Retrievable>) {
            if (abandoned) {
                markMissing(batch)
                return
            }
            val failure = attempt(batch) ?: return
            consecutiveFailures++
            when {
                consecutiveFailures >= abandonAfter -> abandon(batch, failure)
                batch.size == 1 -> {
                    logger.warn("Embedding chunk {} failed ({})", batch.single().id, describe(failure))
                    markMissing(batch, failure)
                }

                else -> split(batch, failure)
            }
        }

        /**
         * One call to the service for [batch]. Records the vectors and resets the failure count on
         * success. Returns the failure, or null on success. A response with the wrong number of
         * vectors is a failure, since the vectors can't be matched to chunks.
         */
        private fun attempt(batch: List<Retrievable>): Exception? = try {
            val vectors = embeddingService.embed(batch.map { it.embeddableValue() })
            check(vectors.size == batch.size) {
                "embedding service returned ${vectors.size} vectors for ${batch.size} texts"
            }
            batch.zip(vectors).forEach { (chunk, vector) -> embeddings[chunk.id] = vector }
            consecutiveFailures = 0
            null
        } catch (e: Exception) {
            e
        }

        /**
         * Embed each half of [batch] after it failed as a whole. The first half takes the extra
         * chunk when the size is odd.
         */
        private fun split(batch: List<Retrievable>, failure: Exception) {
            val half = (batch.size + 1) / 2
            val first = batch.subList(0, half)
            val second = batch.subList(half, batch.size)
            val halves = if (first.size == second.size) "2 batches of ${first.size}"
            else "batches of ${first.size} and ${second.size}"
            logger.warn("Embedding batch of {} chunks failed ({}); retrying as {}", batch.size, describe(failure), halves)
            embed(first)
            embed(second)
        }

        /**
         * Stop calling the service for the rest of this run, and record [batch] missing.
         */
        private fun abandon(batch: List<Retrievable>, failure: Exception) {
            logger.warn(
                "Embedding failed {} times in a row ({}); making no more embedding calls for this write",
                consecutiveFailures,
                describe(failure),
            )
            abandoned = true
            markMissing(batch, failure)
        }

        /**
         * Record [batch] as missing, and [failure] as the cause reported for the run. A batch
         * skipped after abandoning has no failure of its own and keeps the one that caused it.
         */
        private fun markMissing(batch: List<Retrievable>, failure: Exception? = null) {
            missing += batch.map { it.id }
            failure?.let { missingCause = it }
        }
    }
}
