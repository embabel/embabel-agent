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
 * Failed batches are logged but don't prevent other batches from processing.
 */
object EmbeddingBatchGenerator {

    fun generateEmbeddingsInBatches(
        embeddingService: EmbeddingService,
        retrievables: List<Retrievable>,
        batchSize: Int,
        logger: Logger,
    ): Map<String, FloatArray> {
        if (retrievables.isEmpty()) {
            return emptyMap()
        }

        val embeddings = mutableMapOf<String, FloatArray>()
        val batches = retrievables.chunked(batchSize)
        val totalBatches = batches.size

        fun logProgress(current: Int) {
            val progress = VisualizableTask(
                name = "Generating embeddings",
                current = current,
                total = totalBatches
            )
            logger.info(progress.createProgressBar())
        }

        logProgress(0)

        batches.forEachIndexed { index, batch ->
            try {
                val texts = batch.map { it.embeddableValue() }
                val batchEmbeddings = embeddingService.embed(texts)

                batch.zip(batchEmbeddings).forEach { (chunk, embedding) ->
                    embeddings[chunk.id] = embedding
                }

                logProgress(index + 1)
            } catch (e: Exception) {
                logger.warn(
                    "Failed to generate embeddings for batch of {} chunks: {}",
                    batch.size,
                    e.message,
                    e
                )
            }
        }

        return embeddings
    }
}
