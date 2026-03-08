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
import com.embabel.common.ai.model.EmbeddingService
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.LongBuffer
import java.nio.file.Path

/**
 * Local embedding service using ONNX Runtime for inference and DJL HuggingFace tokenizer.
 *
 * Default model: all-MiniLM-L6-v2 (384 dimensions).
 */
@JsonSerialize(`as` = com.embabel.common.ai.model.EmbeddingServiceMetadata::class)
class OnnxEmbeddingService(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val tokenizer: HuggingFaceTokenizer,
    override val dimensions: Int = DEFAULT_DIMENSIONS,
    override val name: String = DEFAULT_MODEL_NAME,
) : EmbeddingService, AutoCloseable {

    /**
     * Convenience constructor that creates the ONNX environment, session and tokenizer from file paths.
     */
    constructor(
        modelPath: Path,
        tokenizerPath: Path,
        dimensions: Int = DEFAULT_DIMENSIONS,
        name: String = DEFAULT_MODEL_NAME,
    ) : this(
        environment = OrtEnvironment.getEnvironment(),
        session = OrtEnvironment.getEnvironment().createSession(modelPath.toString()),
        tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath),
        dimensions = dimensions,
        name = name,
    )

    override val provider: String = PROVIDER

    override fun embed(text: String): FloatArray {
        val encoding = tokenizer.encode(text)
        val inputIds = encoding.ids
        val attentionMask = encoding.attentionMask
        val typeIds = encoding.typeIds
        val seqLen = inputIds.size.toLong()

        val inputIdsTensor = OnnxTensor.createTensor(
            environment, LongBuffer.wrap(inputIds), longArrayOf(1, seqLen),
        )
        val attentionMaskTensor = OnnxTensor.createTensor(
            environment, LongBuffer.wrap(attentionMask), longArrayOf(1, seqLen),
        )
        val typeIdsTensor = OnnxTensor.createTensor(
            environment, LongBuffer.wrap(typeIds), longArrayOf(1, seqLen),
        )

        try {
            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to attentionMaskTensor,
                "token_type_ids" to typeIdsTensor,
            )
            val result = session.run(inputs)
            try {
                // sentence-transformers ONNX models output last_hidden_state;
                // apply mean pooling weighted by attention mask.
                @Suppress("UNCHECKED_CAST")
                val lastHiddenState = result[0].value as Array<Array<FloatArray>>
                return meanPool(lastHiddenState[0], attentionMask)
            } finally {
                result.close()
            }
        } finally {
            inputIdsTensor.close()
            attentionMaskTensor.close()
            typeIdsTensor.close()
        }
    }

    override fun embed(texts: List<String>): List<FloatArray> = texts.map { embed(it) }

    override fun close() {
        session.close()
    }

    companion object {
        const val PROVIDER = "onnx"
        const val DEFAULT_MODEL_NAME = "all-MiniLM-L6-v2"
        const val DEFAULT_DIMENSIONS = 384

        /**
         * Mean pooling: average token embeddings weighted by attention mask.
         */
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
