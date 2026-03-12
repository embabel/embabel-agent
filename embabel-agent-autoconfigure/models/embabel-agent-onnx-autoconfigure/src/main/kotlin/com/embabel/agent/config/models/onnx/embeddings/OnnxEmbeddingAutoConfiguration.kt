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
import jakarta.annotation.PreDestroy
import java.nio.file.Path
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for the ONNX embedding service.
 *
 * Downloads model and tokenizer files from HuggingFace on first use,
 * caches them locally, and registers an [OnnxEmbeddingService] bean
 * via [ConfigurableBeanFactory.registerSingleton] so the bean name
 * matches the model name used in [ProviderInitialization].
 *
 * **Why this class does NOT inject `aiModelHttpRequestFactory`:**
 * Other providers (Anthropic, OpenAI, Ollama) use the shared factory for
 * ongoing API calls where shared proxy/SSL/timeout settings are appropriate.
 * ONNX model downloads are fundamentally different: they are one-time large
 * file downloads from HuggingFace, which returns HTTP 302 redirects to CDN
 * URLs. The Reactor Netty client created by [NettyClientAutoConfiguration]
 * does not follow redirects by default (`HttpClient.create()` requires an
 * explicit `.followRedirect(true)`), so using it here would cause downloads
 * to fail silently. The default JDK-based `RestClient` follows redirects
 * out of the box and is the correct choice for this use case.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(OnnxEmbeddingService::class)
@ConditionalOnProperty(
    prefix = "embabel.agent.platform.models.onnx.embeddings",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
@EnableConfigurationProperties(OnnxEmbeddingProperties::class)
class OnnxEmbeddingAutoConfiguration(
    private val configurableBeanFactory: ConfigurableBeanFactory,
) {

    private val logger = LoggerFactory.getLogger(OnnxEmbeddingAutoConfiguration::class.java)

    private var embeddingService: OnnxEmbeddingService? = null

    @Bean
    fun onnxEmbeddingInitializer(properties: OnnxEmbeddingProperties): ProviderInitialization {
        val cacheDir = Path.of(properties.cacheDir, properties.modelName)
        val modelPath = OnnxModelLoader.resolve(properties.modelUri, cacheDir, "model.onnx")
        val tokenizerPath = OnnxModelLoader.resolve(properties.tokenizerUri, cacheDir, "tokenizer.json")
        logger.info(
            "Initializing ONNX embedding service: model={}, dimensions={}, cache={}",
            properties.modelName, properties.dimensions, cacheDir,
        )
        val service = OnnxEmbeddingService.create(
            modelPath = modelPath,
            tokenizerPath = tokenizerPath,
            dimensions = properties.dimensions,
            name = properties.modelName,
        )
        configurableBeanFactory.registerSingleton(properties.modelName, service)
        embeddingService = service
        logger.info("ONNX embedding service registered: {}", properties.modelName)
        return ProviderInitialization(
            provider = OnnxEmbeddingService.PROVIDER,
            registeredLlms = emptyList(),
            registeredEmbeddings = listOf(
                RegisteredModel(beanName = properties.modelName, modelId = properties.modelName),
            ),
        )
    }

    @PreDestroy
    fun cleanup() {
        embeddingService?.close()
    }
}
