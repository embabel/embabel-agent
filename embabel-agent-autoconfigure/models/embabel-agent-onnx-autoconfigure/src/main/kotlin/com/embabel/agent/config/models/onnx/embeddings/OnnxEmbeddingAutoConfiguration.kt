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
package com.embabel.agent.config.models.onnx.embeddings

import com.embabel.agent.onnx.OnnxModelLoader
import com.embabel.agent.onnx.embeddings.OnnxEmbeddingService
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.EmbeddingService
import java.nio.file.Path
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for the ONNX embedding service.
 *
 * Downloads model and tokenizer files from HuggingFace on first use,
 * caches them locally, and registers an [OnnxEmbeddingService] bean.
 */
@Configuration
@ConditionalOnClass(OnnxEmbeddingService::class)
@ConditionalOnProperty(
    prefix = "embabel.onnx.embeddings",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(OnnxEmbeddingProperties::class)
class OnnxEmbeddingAutoConfiguration {

    private val logger = LoggerFactory.getLogger(OnnxEmbeddingAutoConfiguration::class.java)

    @Bean
    fun onnxEmbeddingService(properties: OnnxEmbeddingProperties): EmbeddingService {
        val cacheDir = Path.of(properties.cacheDir, properties.modelName)
        val modelPath = OnnxModelLoader.resolve(properties.modelUri, cacheDir, "model.onnx")
        val tokenizerPath = OnnxModelLoader.resolve(properties.tokenizerUri, cacheDir, "tokenizer.json")

        logger.info(
            "Initializing ONNX embedding service: model={}, dimensions={}, cache={}",
            properties.modelName, properties.dimensions, cacheDir,
        )

        return OnnxEmbeddingService(
            modelPath = modelPath,
            tokenizerPath = tokenizerPath,
            dimensions = properties.dimensions,
            name = properties.modelName,
        )
    }

    @Bean
    fun onnxEmbeddingInitializer(
        onnxEmbeddingService: EmbeddingService,
        properties: OnnxEmbeddingProperties,
    ): ProviderInitialization {
        logger.info("ONNX embedding service registered: {}", properties.modelName)
        return ProviderInitialization(
            provider = OnnxEmbeddingService.PROVIDER,
            registeredLlms = emptyList(),
            registeredEmbeddings = listOf(
                RegisteredModel(beanName = properties.modelName, modelId = properties.modelName),
            ),
        )
    }
}
