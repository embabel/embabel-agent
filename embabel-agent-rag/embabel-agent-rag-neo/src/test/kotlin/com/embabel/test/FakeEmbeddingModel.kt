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
package com.embabel.test

import com.embabel.common.util.generateRandomFloatArray
import org.springframework.ai.document.Document
import org.springframework.ai.embedding.Embedding
import org.springframework.ai.embedding.EmbeddingModel
import org.springframework.ai.embedding.EmbeddingRequest
import org.springframework.ai.embedding.EmbeddingResponse
import java.util.*

data class FakeEmbeddingModel(
    val dimensions: Int = 1536,
) : EmbeddingModel {

    override fun embed(document: Document): FloatArray {
        return generateRandomFloatArray(dimensions)
    }

    override fun embed(texts: List<String>): MutableList<FloatArray> {
        return texts.map { generateRandomFloatArray(dimensions) }.toMutableList()
    }

    override fun call(request: EmbeddingRequest): EmbeddingResponse {
        val output = LinkedList<Embedding>()
        for (i in request.instructions.indices) {
            output.add(Embedding(generateRandomFloatArray(dimensions), i))
        }
        return EmbeddingResponse(output)
    }
}
