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
package com.embabel.agent.rag.pipeline

import com.embabel.agent.api.common.ActionContext
import com.embabel.agent.api.common.OperationContext
import com.embabel.agent.event.AgentProcessRagEvent
import com.embabel.agent.event.RagEventListener
import com.embabel.agent.event.RagRequestReceivedEvent
import com.embabel.agent.event.RagResponseEvent
import com.embabel.agent.rag.model.Chunk
import com.embabel.agent.rag.model.ContentElement
import com.embabel.agent.rag.model.RetrievableEntity
import com.embabel.agent.rag.pipeline.event.InitialRequestRagPipelineEvent
import com.embabel.agent.rag.pipeline.event.InitialResponseRagPipelineEvent
import com.embabel.agent.rag.service.*
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties

/**
 * Decorates a Rag Service with an enhancement pipeline.
 */
@EnableConfigurationProperties(RagServiceEnhancerProperties::class)
class PipelinedRagServiceEnhancer(
    val ragServiceEnhancerProperties: RagServiceEnhancerProperties,
    val hyDEQueryGenerator: HyDEQueryGenerator,
) : RagServiceEnhancer {

    private val logger = LoggerFactory.getLogger(PipelinedRagServiceEnhancer::class.java)

    init {
        logger.info(
            "Using properties: {}", ragServiceEnhancerProperties
        )
    }

    override fun create(
        operationContext: OperationContext,
        delegate: RagService,
        listener: RagEventListener,
    ): RagService {
        var listenerToUse = listener
        if (operationContext is ActionContext) {
            // For action contexts, we also want to send events to the process listener
            listenerToUse += { ragEvent ->
                operationContext.processContext.onProcessEvent(
                    AgentProcessRagEvent(operationContext.agentProcess, ragEvent)
                )
            }
        }
        return PipelinedRagService(
            operationContext = operationContext,
            delegate = delegate,
            listener = listenerToUse,
        )
    }

    private inner class PipelinedRagService(
        private val operationContext: OperationContext,
        private val delegate: RagService,
        private val listener: RagEventListener,
    ) : RagService {

        override val name
            get() = "pipelined(${delegate.name})"

        override val description
            get() = "Pipelined RAG service wrapping ${delegate.name}: ${delegate.description}"


        override fun search(ragRequest: RagRequest): RagResponse {
            listener.onRagEvent(RagRequestReceivedEvent(ragRequest))
            logger.info("Performing initial rag search for {} using RagService {}", ragRequest, delegate.name)
            val hyDE = ragRequest.hintOfType<HyDE>()
            val initialQuery = hyDE?.let { hyDE ->
                hyDEQueryGenerator.hydeQuery(
                    ragRequest = ragRequest,
                    hyDE = hyDE,
                    llm = ragServiceEnhancerProperties.compressionLlm,
                    ai = operationContext.ai(),
                )
            } ?: ragRequest.query
            // We are more generous in the initial request to give the enhancers more to work with
            val initialRequest = ragRequest.copy(
                query = initialQuery,
                topK = ragRequest.topK * 2,
                similarityThreshold = ragRequest.similarityThreshold / 2,
            )
            listener.onRagEvent(InitialRequestRagPipelineEvent(initialRequest, delegate.name))

            // Return to initial request
            val initialResponse = delegate.search(initialRequest)
                .copy(request = ragRequest)
            listener.onRagEvent(InitialResponseRagPipelineEvent(initialResponse, delegate.name))

            val pipeline = AdaptivePipelineRagResponseEnhancer(
                enhancers = buildList {
                    add(DeduplicatingEnhancer)
                    add(ChunkMergingEnhancer)
                    val resultCompression = ragRequest.hintOfType<ResultCompression>()
                    if (resultCompression?.enabled == true) {
                        add(
                            PromptedContextualCompressionEnhancer(
                                operationContext,
                                ragServiceEnhancerProperties.compressionLlm,
                                ragServiceEnhancerProperties.maxConcurrency,
                            )
                        )
                    }
                    add(RerankingEnhancer(operationContext, ragServiceEnhancerProperties.rerankingLlm))
                    add(FilterEnhancer)
                },
                listener = listener,
            )
            val enhancedRagResponse = pipeline.enhance(initialResponse)
            listener.onRagEvent(RagResponseEvent(enhancedRagResponse))
            logger.info(
                "Final enhanced rag response has {} results: {} chunks, {} other content elements, {} entities",
                enhancedRagResponse.results.size,
                enhancedRagResponse.results.count { it.match is Chunk },
                enhancedRagResponse.results.count { it.match is ContentElement && it.match !is Chunk },
                enhancedRagResponse.results.count { it.match is RetrievableEntity },
            )
            logger.info(
                "Results: {}",
                enhancedRagResponse.results.joinToString("\n") { "- ${it.match}" })
            return enhancedRagResponse
        }

        override fun infoString(
            verbose: Boolean?,
            indent: Int,
        ): String {
            return "PipelinedRagService wrapping ${delegate.infoString(verbose, indent + 2)}"
        }
    }
}
