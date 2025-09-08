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
package com.embabel.agent.rag.pipeline.event

import com.embabel.agent.event.RagEvent
import com.embabel.agent.rag.RagRequest
import com.embabel.agent.rag.RagResponse
import java.time.Instant

/**
 * Events emitted by the RAG pipeline
 * @param description A short human-readable description of the event
 */
abstract class RagPipelineEvent(
    val description: String,
    override val timestamp: Instant = Instant.now(),
) : RagEvent

class InitialRequestRagPipelineEvent(
    override val request: RagRequest,
    val service: String,
) : RagPipelineEvent("Initial RAG request to $service")

class InitialResponseRagPipelineEvent(
    val response: RagResponse,
    val service: String,
) : RagPipelineEvent("Initial RAG response from $service") {

    override val request: RagRequest
        get() = response.request
}

abstract class EnhancementRagPipelineEvent(
    val enhancerName: String,
    description: String,
) : RagPipelineEvent(
    description,
)

class EnhancementStartingRagPipelineEvent(
    val basis: RagResponse,
    enhancerName: String,
) : EnhancementRagPipelineEvent(enhancerName, "Starting enhancement with $enhancerName") {

    override val request: RagRequest
        get() = basis.request
}

class EnhancementCompletedRagPipelineEvent(
    val response: RagResponse,
    enhancerName: String,
) : EnhancementRagPipelineEvent(enhancerName, "Completed enhancement with $enhancerName") {

    override val request: RagRequest
        get() = response.request
}
