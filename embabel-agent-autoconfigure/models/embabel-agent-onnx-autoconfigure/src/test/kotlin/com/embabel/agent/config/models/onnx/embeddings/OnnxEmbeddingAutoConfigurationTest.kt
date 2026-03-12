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
import com.embabel.common.ai.model.EmbeddingService
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.http.client.ClientHttpRequestFactory
import org.springframework.web.client.RestClient

class OnnxEmbeddingAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OnnxEmbeddingAutoConfiguration::class.java))

    @AfterEach
    fun cleanup() {
        try {
            unmockkObject(OnnxModelLoader)
            unmockkObject(OnnxEmbeddingService)
        } catch (_: Exception) {
            // not mocked in every test
        }
    }

    @Test
    fun `no embedding service bean when disabled`() {
        contextRunner
            .withPropertyValues("embabel.agent.platform.models.onnx.embeddings.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(EmbeddingService::class.java)
                assertThat(context).doesNotHaveBean("onnxEmbeddingInitializer")
            }
    }

    @Test
    fun `no beans at all when disabled`() {
        contextRunner
            .withPropertyValues("embabel.agent.platform.models.onnx.embeddings.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(OnnxEmbeddingProperties::class.java)
                assertThat(context).doesNotHaveBean(EmbeddingService::class.java)
                assertThat(context).doesNotHaveBean("onnxEmbeddingInitializer")
            }
    }

    @Test
    fun `initializer uses injected request factory to build RestClient`() {
        mockkObject(OnnxModelLoader)
        mockkObject(OnnxEmbeddingService)

        val restClientSlot = slot<RestClient>()
        val fakePath = Path.of("/tmp/fake-model")
        every {
            OnnxModelLoader.resolve(any(), any(), any(), capture(restClientSlot))
        } returns fakePath

        val mockService = mockk<OnnxEmbeddingService>(relaxed = true)
        every {
            OnnxEmbeddingService.create(
                modelPath = any(),
                tokenizerPath = any(),
                dimensions = any(),
                name = any(),
            )
        } returns mockService

        val mockFactory = mockk<ClientHttpRequestFactory>()

        contextRunner
            .withBean(ClientHttpRequestFactory::class.java, { mockFactory })
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(restClientSlot.isCaptured).isTrue()
            }
    }

    @Test
    fun `initializer works without request factory bean`() {
        mockkObject(OnnxModelLoader)
        mockkObject(OnnxEmbeddingService)

        val restClientSlot = slot<RestClient>()
        val fakePath = Path.of("/tmp/fake-model")
        every {
            OnnxModelLoader.resolve(any(), any(), any(), capture(restClientSlot))
        } returns fakePath

        val mockService = mockk<OnnxEmbeddingService>(relaxed = true)
        every {
            OnnxEmbeddingService.create(
                modelPath = any(),
                tokenizerPath = any(),
                dimensions = any(),
                name = any(),
            )
        } returns mockService

        contextRunner
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(restClientSlot.isCaptured).isTrue()
            }
    }
}
