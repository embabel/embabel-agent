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

import com.embabel.common.ai.model.EmbeddingService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class OnnxEmbeddingAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OnnxEmbeddingAutoConfiguration::class.java))

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
}
