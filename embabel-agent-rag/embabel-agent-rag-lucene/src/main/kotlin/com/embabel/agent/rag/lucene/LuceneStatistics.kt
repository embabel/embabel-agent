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
package com.embabel.agent.rag.lucene

import com.embabel.agent.rag.store.ContentElementRepositoryInfo

/**
 * Statistics about the Lucene RAG service state
 */
data class LuceneStatistics(
    override val chunkCount: Int,
    override val documentCount: Int,
    override val contentElementCount: Int,
    val averageChunkLength: Double,
    override val hasEmbeddings: Boolean,
    val vectorWeight: Double,
    override val isPersistent: Boolean,
    val indexPath: String?,
) : ContentElementRepositoryInfo
