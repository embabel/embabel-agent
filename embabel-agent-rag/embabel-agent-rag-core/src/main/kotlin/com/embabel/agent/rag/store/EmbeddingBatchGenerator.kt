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
            logger.error(
                "{} of {} chunks could not be embedded: {} ({})",
                result.missingChunkIds.size,
                retrievables.size,
                describeIds(result.missingChunkIds),
                describe(result.cause),
                result.cause,
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
     * Number of times [size] must be halved (rounding up) to reach 1: the length of the chain of
     * failed calls that isolates one failing chunk, less one.
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
        private var lastFailure: Exception? = null
        private var consecutiveFailures = 0
        private var abandoned = false

        fun result() = EmbeddingBatchResult(
            embeddings = embeddings.toMap(),
            missingChunkIds = missing.toList(),
            cause = lastFailure.takeIf { missing.isNotEmpty() },
        )

        fun embed(batch: List<Retrievable>) {
            if (abandoned) {
                missing += batch.map { it.id }
                return
            }
            val failure = attempt(batch) ?: return
            lastFailure = failure
            consecutiveFailures++
            when {
                consecutiveFailures >= abandonAfter -> abandon(batch, failure)
                batch.size == 1 -> {
                    logger.warn("Embedding chunk {} failed ({})", batch.single().id, describe(failure))
                    missing += batch.single().id
                }

                else -> split(batch, failure)
            }
        }

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

        private fun abandon(batch: List<Retrievable>, failure: Exception) {
            logger.warn(
                "Embedding failed {} times in a row ({}); making no more embedding calls for this write",
                consecutiveFailures,
                describe(failure),
            )
            abandoned = true
            missing += batch.map { it.id }
        }
    }
}
