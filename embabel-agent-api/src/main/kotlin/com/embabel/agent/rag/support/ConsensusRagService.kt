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
package com.embabel.agent.rag.support

import com.embabel.agent.rag.RagRequest
import com.embabel.agent.rag.RagResponse
import com.embabel.agent.rag.RagService
import com.embabel.agent.rag.WritableRagService
import com.embabel.common.util.loggerFor
import org.springframework.ai.document.Document

/**
 * Rag service that combines multiple RagServices and returns the best results
 */
class ConsensusRagService(
    private val ragServices: List<RagService>,
) : WritableRagService {
    override val name: String
        get() = "consensus: ${ragServices.joinToString(",") { it.name }}"

    override fun search(ragRequest: RagRequest): RagResponse {
        val allResults = ragServices.flatMap { ragService ->
            ragService.search(ragRequest).results
        }
        // TODO Count and commend duplicates
        return RagResponse(
            request = ragRequest,
            service = name,
            results = allResults,
        )
    }

    override fun accept(documents: List<Document>) {
        val writableRagServices = ragServices.filterIsInstance<WritableRagService>()
        if (writableRagServices.isEmpty()) {
            loggerFor<ConsensusRagService>().warn(
                "No writable RAG services available for accepting {} documents", documents.size,
            )
        }
        writableRagServices.forEach { ragService ->
            ragService.accept(documents)
        }
    }

    override val description: String
        get() = "Consensus of [${ragServices.joinToString(",") { it.description }}]"

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String =
        if (ragServices.isEmpty()) "No RAG services" else
            "Consensus of ${
                ragServices.joinToString(",") {
                    it.infoString(verbose = verbose, indent = 1)
                }
            }"
}
