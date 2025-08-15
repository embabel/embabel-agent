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
