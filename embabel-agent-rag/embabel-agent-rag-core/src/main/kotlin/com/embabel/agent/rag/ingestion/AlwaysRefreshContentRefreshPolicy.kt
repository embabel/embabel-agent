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
package com.embabel.agent.rag.ingestion

import com.embabel.agent.rag.model.NavigableDocument
import com.embabel.agent.rag.store.ChunkingContentElementRepository

/**
 * Always refresh content, regardless of whether it already exists.
 * Useful for content that changes frequently and should always be re-fetched.
 */
object AlwaysRefreshContentRefreshPolicy : ContentRefreshPolicy {

    override fun shouldReread(
        repository: ChunkingContentElementRepository,
        rootUri: String,
    ): Boolean = true

    override fun shouldRefreshDocument(
        repository: ChunkingContentElementRepository,
        root: NavigableDocument,
    ): Boolean = true
}
