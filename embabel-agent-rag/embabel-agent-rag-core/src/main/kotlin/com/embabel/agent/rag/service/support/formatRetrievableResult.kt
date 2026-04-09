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

import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.Fact
import com.embabel.agent.rag.model.NamedEntityData
import com.embabel.agent.rag.model.Retrievable
import com.embabel.common.core.types.SimilarityResult

/**
 * Formats a single [SimilarityResult] of a [Retrievable] into a human-readable string.
 * Shared by [com.embabel.agent.rag.service.SimpleRetrievableResultsFormatter] and
 * [TokenBudgetRetrievableResultsFormatter].
 */
internal fun formatRetrievableResult(result: SimilarityResult<out Retrievable>): String {
    val formattedScore = "%.2f".format(result.score)
    return when (val match = result.match) {
        is NamedEntityData -> "$formattedScore: ${match.embeddableValue()}"
        is Chunk -> {
            val urlHeader = match.uri?.let { "url: $it\n" } ?: ""
            "chunkId: ${match.id} $urlHeader$formattedScore - ${match.text}"
        }
        is Fact -> "$formattedScore: fact - ${match.assertion}"
        else -> "$formattedScore: ${match.javaClass.simpleName} - ${match.infoString(verbose = true)}"
    }
}
