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

import com.embabel.agent.rag.Chunk
import com.embabel.agent.rag.ContentElement

/**
 * Implemented by services that can retrieve Chunks by id.
 */
interface ContentElementRepository {

    fun findChunksById(chunkIds: List<String>): List<Chunk>

    fun findById(id: String): ContentElement?

    fun save(element: ContentElement): ContentElement
}
