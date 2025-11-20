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
package com.embabel.agent.rag.ingestion

import com.embabel.agent.rag.store.ChunkingContentElementRepository
import com.embabel.common.core.types.HasInfoString

data class IngestionResult(
    val storesWrittenTo: Set<String>,
    val chunkIds: List<String>,
) {

    val documentsWritten: Int get() = chunkIds.size

    fun success(): Boolean {
        return storesWrittenTo.isNotEmpty()
    }
}

interface Ingester : HasInfoString {

    /**
     * Is this ingester presently active?
     */
    fun active(): Boolean

    val stores: List<ChunkingContentElementRepository>

    /**
     * Ingest the resource at the given path.
     * Use Spring Resource conventions
     */
    fun ingest(resourcePath: String): IngestionResult
}
