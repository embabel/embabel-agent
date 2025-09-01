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
package com.embabel.agent.experimental.domain.persistence

import com.embabel.common.core.types.SimilarityResult
import com.embabel.common.core.types.ZeroToOne

enum class Cardinality {
    ONE,
    MANY,
}

/**
 * Find entities matching a description.
 * @param content The content to match against.
 * @param cardinality The expected cardinality of the result.
 */
data class FindEntitiesRequest(
    val content: String,
    val cardinality: Cardinality = Cardinality.ONE,
)

/**
 * Match for an entity
 * @param T The type of the entity being matched.
 * @param match The matched entity.
 * @param score The confidence score of the match, between 0 and 1.
 * @param source The source of the match, e.g., the repository method that provided the match.
 */
data class EntityMatch<T>(
    override val match: T,
    override val score: ZeroToOne,
    val source: String,
) : SimilarityResult<T>

data class FindEntitiesResponse<T>(
    val request: FindEntitiesRequest,
    val matches: List<EntityMatch<T>>,
)

/**
 * Allows querying using natural language.
 * Querying using natural language is subjective, thus
 * results have a confidence score.
 */
interface NaturalLanguageRepository<T> {

    /**
     * Find entities matching this query
     */
    fun find(
        findEntitiesRequest: FindEntitiesRequest,
    ): FindEntitiesResponse<T>

    /**
     * I'm feeling lucky. Try to find one
     */
    fun findOne(
        description: String,
        confidenceCutOff: ZeroToOne = 1.0,
    ): T? {
        val matches = find(
            findEntitiesRequest = FindEntitiesRequest(
                content = description,
                cardinality = Cardinality.ONE,
            )
        )
        return matches.matches
            .firstOrNull { it.score >= confidenceCutOff }
            ?.match
    }
}
