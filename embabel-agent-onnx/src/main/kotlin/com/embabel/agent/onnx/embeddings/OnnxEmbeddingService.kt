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
package com.embabel.agent.onnx.embeddings

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.embabel.common.ai.model.EmbeddingService
import com.embabel.common.ai.model.EmbeddingServiceMetadata
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import java.nio.LongBuffer
import java.nio.file.Path

/**
 * Local embedding service using ONNX Runtime for inference and DJL HuggingFace tokenizer.
 *
 * Default model: all-MiniLM-L6-v2 (384 dimensions).
 */
@JsonSerialize(`as` = EmbeddingServiceMetadata::class)
class OnnxEmbeddingService(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: HuggingFaceTokenizer,
    override val dimensions: Int = DEFAULT_DIMENSIONS,
    override val name: String = DEFAULT_MODEL_NAME,
) : EmbeddingService, AutoCloseable {

    override val provider: String = PROVIDER

    override fun embed(text: String): FloatArray = embed(listOf(text)).first()

    override fun embed(texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val encodings = texts.map { tokenizer.encode(it) }
        val batchSize = encodings.size
        val maxSeqLen = encodings.maxOf { it.ids.size }
        val allInputIds = LongArray(batchSize * maxSeqLen)
        val allAttentionMask = LongArray(batchSize * maxSeqLen)
        val allTypeIds = LongArray(batchSize * maxSeqLen)
        for (i in encodings.indices) {
            val enc = encodings[i]
            val offset = i * maxSeqLen
            enc.ids.copyInto(allInputIds, offset)
            enc.attentionMask.copyInto(allAttentionMask, offset)
            enc.typeIds.copyInto(allTypeIds, offset)
            // Remaining positions stay 0 (padding)
        }
        val shape = longArrayOf(batchSize.toLong(), maxSeqLen.toLong())
        val inputIdsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(allInputIds), shape)
        val attentionMaskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(allAttentionMask), shape)
        val typeIdsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(allTypeIds), shape)
        return inputIdsTensor.use {
            attentionMaskTensor.use {
                typeIdsTensor.use {
                    val inputs = mapOf(
                        "input_ids" to inputIdsTensor,
                        "attention_mask" to attentionMaskTensor,
                        "token_type_ids" to typeIdsTensor,
                    )
                    session.run(inputs).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val lastHiddenState = result[0].value as Array<Array<FloatArray>>
                        encodings.indices.map { i ->
                            val paddedMask = allAttentionMask.copyOfRange(i * maxSeqLen, (i + 1) * maxSeqLen)
                            meanPool(lastHiddenState[i], paddedMask)
                        }
                    }
                }
            }
        }
    }

    override fun close() {
        session.close()
        tokenizer.close()
    }

    companion object {
        const val PROVIDER = "onnx"
        const val DEFAULT_MODEL_NAME = "all-MiniLM-L6-v2"
        const val DEFAULT_DIMENSIONS = 384

        @JvmStatic
        fun create(
            modelPath: Path,
            tokenizerPath: Path,
            dimensions: Int = DEFAULT_DIMENSIONS,
            name: String = DEFAULT_MODEL_NAME,
        ): OnnxEmbeddingService {
            val env = OrtEnvironment.getEnvironment()
            return OnnxEmbeddingService(
                environment = env,
                session = env.createSession(modelPath.toString()),
                tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath),
                dimensions = dimensions,
                name = name,
            )
        }

        internal fun meanPool(tokenEmbeddings: Array<FloatArray>, attentionMask: LongArray): FloatArray {
            val dim = tokenEmbeddings[0].size
            val result = FloatArray(dim)
            var maskSum = 0f
            for (i in tokenEmbeddings.indices) {
                val mask = attentionMask[i].toFloat()
                maskSum += mask
                for (j in 0 until dim) {
                    result[j] += tokenEmbeddings[i][j] * mask
                }
            }
            if (maskSum > 0f) {
                for (j in 0 until dim) {
                    result[j] /= maskSum
                }
            }
            return result
        }
    }
}
