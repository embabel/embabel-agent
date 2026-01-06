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
package com.embabel.agent.rag

import com.embabel.agent.rag.pipeline.HyDEQueryGenerator
import com.embabel.agent.rag.pipeline.PipelinedRagServiceEnhancer
import com.embabel.agent.rag.pipeline.support.LlmHyDEQueryGenerator
import com.embabel.agent.rag.service.RagServiceEnhancer
import com.embabel.agent.rag.service.RagServiceEnhancerProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configure necessary beans for the RAG pipeline
 * if they are not already present. Allows users to override
 */
@Configuration
class RagPipelineConfiguration {

    @Bean
    @ConditionalOnMissingBean(RagServiceEnhancer::class)
    fun ragServiceEnhancer(
        properties: RagServiceEnhancerProperties,
        hyDEQueryGenerator: HyDEQueryGenerator,
    ): RagServiceEnhancer {
        return PipelinedRagServiceEnhancer(properties, hyDEQueryGenerator)
    }

    @Bean
    @ConditionalOnMissingBean(HyDEQueryGenerator::class)
    fun hyDEQueryGenerator(): HyDEQueryGenerator {
        return LlmHyDEQueryGenerator(promptPath = "default_hyde")
    }
}
