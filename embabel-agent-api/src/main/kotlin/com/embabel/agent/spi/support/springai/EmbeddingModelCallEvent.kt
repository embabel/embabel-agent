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
package com.embabel.agent.spi.support.springai

import com.embabel.agent.api.event.EmbeddingEvent
import com.embabel.common.ai.model.EmbeddingServiceMetadata
import org.springframework.ai.embedding.EmbeddingRequest
import java.time.Instant

/**
 * Spring AI low level event: embedding model call.
 *
 * Emitted at the actual `EmbeddingModel.call(EmbeddingRequest)` point, capturing
 * the fully-built Spring AI request (with its options) sent to the provider.
 *
 * Independent of any `AgentProcess` so callers without an active agent
 * (HTTP controllers, batch jobs, schedulers) still see it.
 */
class EmbeddingModelCallEvent(
    override val embeddingMetadata: EmbeddingServiceMetadata,
    override val id: String,
    val request: EmbeddingRequest,
    override val timestamp: Instant = Instant.now(),
) : EmbeddingEvent {

    override val inputs: List<String> get() = request.instructions

    override fun toString(): String =
        "EmbeddingModelCallEvent(id=$id, model=${embeddingMetadata.name}, inputs=${request.instructions.size})"
}
