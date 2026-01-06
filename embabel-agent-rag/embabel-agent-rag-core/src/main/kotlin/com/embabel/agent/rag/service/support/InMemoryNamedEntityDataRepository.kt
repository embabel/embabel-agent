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

import com.embabel.agent.core.DataDictionary
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.service.EntityIdentifier
import com.embabel.agent.rag.service.NamedEntityDataRepository
import com.embabel.agent.rag.service.RelationshipData
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.TextSimilaritySearchRequest
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * In-memory implementation of [NamedEntityDataRepository] with optional vector search support.
 *
 * Useful for testing and simple use cases where persistence is not required.
 * Thread-safe using ConcurrentHashMap.
 *
 * When [embeddingService] is provided:
 * - Embeddings are computed and cached on save
 * - [vectorSearch] performs cosine similarity search
 *
 * When [embeddingService] is null:
 * - [vectorSearch] returns empty results
 *
 * @param dataDictionary Data dictionary for typed entity lookups
 * @param embeddingService Optional service for computing text embeddings
 * @param objectMapper ObjectMapper for typed hydration
 */
open class InMemoryNamedEntityDataRepository @JvmOverloads constructor(
    override val dataDictionary: DataDictionary,
    private val embeddingService: EmbeddingService? = null,
    override val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule(),
) : NamedEntityDataRepository {

    private val entities = ConcurrentHashMap<String, NamedEntityData>()
    private val embeddings = ConcurrentHashMap<String, FloatArray>()

    override fun save(entity: NamedEntityData): NamedEntityData {
        entities[entity.id] = entity
        embeddingService?.let { service ->
            val textToEmbed = buildEmbeddingText(entity)
            embeddings[entity.id] = service.embed(textToEmbed)
        }
        return entity
    }

    override fun findById(id: String): NamedEntityData? = entities[id]

    override fun vectorSearch(request: TextSimilaritySearchRequest): List<SimilarityResult<NamedEntityData>> {
        val service = embeddingService ?: return emptyList()
        val queryEmbedding = service.embed(request.query)

        return embeddings.mapNotNull { (id, embedding) ->
            val entity = entities[id] ?: return@mapNotNull null
            val similarity = cosineSimilarity(queryEmbedding, embedding)
            if (similarity >= request.similarityThreshold) {
                SimilarityResult(match = entity, score = similarity)
            } else {
                null
            }
        }
            .sortedByDescending { it.score }
            .take(request.topK)
    }

    override fun textSearch(request: TextSimilaritySearchRequest): List<SimilarityResult<NamedEntityData>> {
        return entities.values
            .filter { entity ->
                entity.name.contains(request.query, ignoreCase = true) ||
                        entity.description.contains(request.query, ignoreCase = true)
            }
            .map { entity -> SimilarityResult(match = entity, score = 1.0) }
            .take(request.topK)
    }

    override val luceneSyntaxNotes: String = "Basic substring matching only"

    override fun createRelationship(a: EntityIdentifier, b: EntityIdentifier, relationship: RelationshipData) {
        // No-op for in-memory implementation
    }

    override fun delete(id: String): Boolean {
        embeddings.remove(id)
        return entities.remove(id) != null
    }

    override fun findByLabel(label: String): List<NamedEntityData> =
        entities.values.filter { it.labels().contains(label) }

    /**
     * Clear all entities and cached embeddings. Useful for testing.
     */
    fun clear() {
        entities.clear()
        embeddings.clear()
    }

    /**
     * Number of entities stored.
     */
    val size: Int get() = entities.size

    private fun buildEmbeddingText(entity: NamedEntityData): String {
        val parts = mutableListOf(entity.name, entity.description)
        parts.addAll(entity.labels())
        return parts.joinToString(" ")
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Double {
        if (a.size != b.size) return 0.0

        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = sqrt(normA) * sqrt(normB)
        return if (denominator == 0.0) 0.0 else dotProduct / denominator
    }
}
