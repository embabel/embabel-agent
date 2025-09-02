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
package com.embabel.agent.rag

import org.jetbrains.annotations.ApiStatus

sealed interface ExplorationRequest

data class DepthExplorationRequest(
    val depth: Int,
) : ExplorationRequest

data class PathsExplorationRequest(
    val paths: List<String>,
) : ExplorationRequest

/**
 * Rag service that supports navigation in a graph of retrievable objects.
 * This may not be supported by all RAG services.
 * It need not be a graph but could be implemented by a relational database or other structure.
 */
@ApiStatus.Experimental
interface NavigableRagService : RagService {

    /**
     * Explore the graph of retrievable objects around the given retrievable object.
     */
    fun explore(
        retrievable: Retrievable,
        explorationRequest: ExplorationRequest,
    ): Retrievable
}
