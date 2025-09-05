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
package com.embabel.agent.config

import com.embabel.agent.rag.RagService
import com.embabel.agent.rag.RagServiceEnhancer
import com.embabel.agent.rag.WritableRagService
import com.embabel.agent.rag.ingestion.Ingester
import com.embabel.agent.rag.ingestion.MultiIngester
import com.embabel.agent.rag.pipeline.PipelinedRagServiceEnhancer
import com.embabel.agent.rag.pipeline.RagEnhancerProperties
import com.embabel.agent.rag.support.ConsensusRagService
import com.embabel.agent.rag.support.SpringVectorStoreRagService
import org.springframework.ai.vectorstore.VectorStore
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
class RagServiceConfiguration {

    @Bean
    @ConditionalOnMissingBean(RagService::class)
    @ConditionalOnBean(VectorStore::class)
    fun defaultSpringVectorStore(vectorStore: VectorStore): RagService {
        return SpringVectorStoreRagService(vectorStore, "Default Vector Store RAG Service")
    }

    @Bean
    @Primary
    fun consensusRagService(
        ragServices: List<RagService>,
    ): RagService {
        return ConsensusRagService(ragServices)
    }

    @Bean
    @ConditionalOnMissingBean(RagServiceEnhancer::class)
    fun ragServiceEnhancer(properties: RagEnhancerProperties): RagServiceEnhancer {
        return PipelinedRagServiceEnhancer(properties)
    }

    @Bean
    @Primary
    fun ingester(
        ragServices: List<WritableRagService>,
    ): Ingester {
        return MultiIngester(ragServices)
    }

}
