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
package com.embabel.agent.rag.service.support

import com.embabel.agent.rag.model.Retrievable
import com.embabel.agent.rag.service.CoreSearchOperations
import com.embabel.chat.Asset
import com.embabel.chat.AssetTracker
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.SimpleSimilaritySearchResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory search operations for conversation assets.
 * Provides text search (always available) and vector search (when EmbeddingService is provided).
 *
 * Text search uses simple case-insensitive matching on asset text representation.
 * Vector search uses brute-force cosine similarity with cached embeddings.
 *
 * @param assetTracker The asset tracker to search
 * @param embeddingService Optional embedding service for vector search; if null, only text search is available
 */
class AssetTrackerSearchOperations(
    val assetTracker: AssetTracker,
    private val embeddingService: EmbeddingService? = null,
) : CoreSearchOperations {

    private val embeddingCache = ConcurrentHashMap<String, FloatArray>()

    override fun supportsType(type: String): Boolean {
        return type == Asset::class.java.simpleName ||
                type == AssetRetrievable::class.java.simpleName
    }

    override val luceneSyntaxNotes: String
        get() = "Simple text matching only. Supports case-insensitive substring search. " +
                "No Lucene query syntax supported."

    @Suppress("UNCHECKED_CAST")
    override fun <T : Retrievable> vectorSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        if (embeddingService == null) {
            return emptyList()
        }

        val queryEmbedding = embeddingService.embed(request.query)
        val assets = assetTracker.assets

        return assets
            .map { asset ->
                val assetEmbedding = getOrComputeEmbedding(asset)
                val score = VectorMath.cosineSimilarity(queryEmbedding, assetEmbedding)
                asset to score
            }
            .filter { (_, score) -> score >= request.similarityThreshold }
            .sortedByDescending { (_, score) -> score }
            .take(request.topK)
            .mapNotNull { (asset, score) ->
                val retrievable = AssetRetrievable(asset)
                if (clazz.isInstance(retrievable)) {
                    SimpleSimilaritySearchResult(
                        match = retrievable as T,
                        score = score,
                    )
                } else null
            }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : Retrievable> textSearch(
        request: TextSimilaritySearchRequest,
        clazz: Class<T>,
    ): List<SimilarityResult<T>> {
        val queryLower = request.query.lowercase()
        val queryTerms = queryLower.split(Regex("\\s+")).filter { it.isNotBlank() }
        val assets = assetTracker.assets

        return assets
            .map { asset ->
                val text = getAssetText(asset).lowercase()
                val score = calculateTextMatchScore(text, queryTerms)
                asset to score
            }
            .filter { (_, score) -> score >= request.similarityThreshold }
            .sortedByDescending { (_, score) -> score }
            .take(request.topK)
            .mapNotNull { (asset, score) ->
                val retrievable = AssetRetrievable(asset)
                if (clazz.isInstance(retrievable)) {
                    SimpleSimilaritySearchResult(
                        match = retrievable as T,
                        score = score,
                    )
                } else null
            }
    }

    /**
     * Calculate a simple text match score based on term matching.
     * Score is between 0.0 and 1.0 based on percentage of query terms found.
     */
    private fun calculateTextMatchScore(text: String, queryTerms: List<String>): Double {
        if (queryTerms.isEmpty()) return 0.0
        val matchedTerms = queryTerms.count { term -> text.contains(term) }
        return matchedTerms.toDouble() / queryTerms.size
    }

    /**
     * Get the text representation of an asset for search purposes.
     */
    private fun getAssetText(asset: Asset): String {
        return asset.reference().contribution()
    }

    /**
     * Get cached embedding or compute and cache it.
     */
    private fun getOrComputeEmbedding(asset: Asset): FloatArray {
        return embeddingCache.computeIfAbsent(asset.id) {
            embeddingService!!.embed(getAssetText(asset))
        }
    }

    /**
     * Clear the embedding cache. Useful when assets are removed or updated.
     */
    fun clearEmbeddingCache() {
        embeddingCache.clear()
    }

    /**
     * Pre-compute and cache embeddings for all current assets.
     * Call this to avoid computing embeddings during search.
     */
    fun precomputeEmbeddings() {
        if (embeddingService == null) return
        assetTracker.assets.forEach { asset ->
            getOrComputeEmbedding(asset)
        }
    }
}

/**
 * Wrapper to make Asset implement Retrievable for search results.
 */
class AssetRetrievable(
    private val asset: Asset,
) : Retrievable {

    override val id: String
        get() = asset.id

    override val uri: String?
        get() = null

    override val metadata: Map<String, Any?>
        get() = mapOf(
            "name" to asset.reference().name,
            "description" to asset.reference().description,
        )

    override fun embeddableValue(): String {
        return asset.reference().contribution()
    }

    override fun infoString(verbose: Boolean?, indent: Int): String {
        val ref = asset.reference()
        return "Asset(id=${asset.id}, name=${ref.name})"
    }

    /**
     * Get the underlying Asset.
     */
    fun getAsset(): Asset = asset
}
