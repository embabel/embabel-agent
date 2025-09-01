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
package com.embabel.agent.rag

import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.test.ai.FakeEmbeddingModel
import com.embabel.common.util.indent
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Service

@Configuration
@Profile("test")
class FakeEmbeddingConfig {

    @Bean
    fun fakeEmbeddingService(): EmbeddingService {
        return EmbeddingService("test", "test-provider", FakeEmbeddingModel())
    }
}

@Service
@Profile("test")
class FakeRagService : RagService {
    override val name: String
        get() = "test"

    override fun search(ragRequest: RagRequest): RagResponse {
        return RagResponse(
            request = ragRequest,
            service = name,
            results = emptyList(),
        )
    }

    override val description: String
        get() = "test RAG"

    override fun infoString(
        verbose: Boolean?,
        indent: Int,
    ): String {
        return "RAG service: $name".indent(indent)
    }
}
