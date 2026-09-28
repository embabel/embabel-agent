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
import org.slf4j.Logger

/**
 * State for one [EmbeddingBatchGenerator.embedInBatches] call: the embeddings so far, the
 * chunks missing, and the run of consecutive failed calls that decides when to stop calling.
 */
internal class EmbeddingBatchRun(
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
                logger.warn("Embedding chunk {} failed ({})", batch.single().id, describeFailure(failure))
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
        logger.warn("Embedding batch of {} chunks failed ({}); retrying as {}", batch.size, describeFailure(failure), halves)
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
            describeFailure(failure),
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


/** A failure's message, or its type when it has none, for a log line. */
internal fun describeFailure(e: Throwable?): String = e?.message ?: e?.javaClass?.simpleName ?: "unknown"
