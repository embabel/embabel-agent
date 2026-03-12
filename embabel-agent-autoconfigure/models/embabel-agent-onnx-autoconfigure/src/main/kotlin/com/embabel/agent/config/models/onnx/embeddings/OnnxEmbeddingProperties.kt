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

import com.embabel.agent.onnx.embeddings.OnnxEmbeddingService
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the ONNX embedding service.
 *
 * Model and tokenizer files are downloaded from HuggingFace on first use
 * and cached locally. Set [modelUri] and [tokenizerUri] to local file:// URIs
 * to skip downloading.
 */
@ConfigurationProperties(prefix = "embabel.agent.platform.models.onnx.embeddings")
class OnnxEmbeddingProperties {

    /** Whether the ONNX embedding service is enabled. */
    var enabled: Boolean = true

    /** URI of the ONNX model file (supports https:// and file://). */
    var modelUri: String = DEFAULT_MODEL_URI

    /** URI of the tokenizer.json file (supports https:// and file://). */
    var tokenizerUri: String = DEFAULT_TOKENIZER_URI

    /** Local cache directory for downloaded model files. */
    var cacheDir: String = System.getProperty("user.home") + "/.embabel/models"

    /** Embedding vector dimensions (must match the model). */
    var dimensions: Int = OnnxEmbeddingService.DEFAULT_DIMENSIONS

    /** Logical name for the embedding model (used for bean registration). */
    var modelName: String = OnnxEmbeddingService.DEFAULT_MODEL_NAME

    companion object {
        private const val HF_BASE = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main"
        const val DEFAULT_MODEL_URI = "$HF_BASE/onnx/model.onnx"
        const val DEFAULT_TOKENIZER_URI = "$HF_BASE/tokenizer.json"
    }
}
