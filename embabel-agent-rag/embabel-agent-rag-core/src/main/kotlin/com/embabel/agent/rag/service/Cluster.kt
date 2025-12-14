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
package com.embabel.agent.rag.service

import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.ZeroToOne

/**
 * Cluster of similar things
 */
data class Cluster<E>(
    val anchor: E,
    val similar: List<SimilarityResult<E>>,
)

data class ClusterRetrievalRequest<E> @JvmOverloads constructor(
    val similarityThreshold: ZeroToOne = 0.7,
    val topK: Int = 10,
    val vectorIndex: String = "embabel-entity-index",
) {

    fun withSimilarityThreshold(similarityThreshold: ZeroToOne): ClusterRetrievalRequest<E> =
        copy(similarityThreshold = similarityThreshold)

    fun withTopK(topK: Int): ClusterRetrievalRequest<E> = copy(topK = topK)
}

interface ClusterFinder {

    /**
     * Find all clusters
     */
    fun <E> findClusters(opts: ClusterRetrievalRequest<E>): List<Cluster<E>>
}
