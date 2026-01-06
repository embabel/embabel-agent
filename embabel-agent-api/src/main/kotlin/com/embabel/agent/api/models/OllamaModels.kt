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
package com.embabel.agent.api.models

/**
 * Popular Ollama models as constants for easy reference and type safety.
 * These represent the most commonly used models in the Ollama ecosystem.
 */
class OllamaModels {

    companion object {
        //Provider
        const val PROVIDER = "Ollama"

        // Llama Models
        const val LLAMA_31_8B = "llama3.1:8b"
        const val LLAMA_31_70B = "llama3.1:70b"
        const val LLAMA_31_405B = "llama3.1:405b"
        const val LLAMA_3_8B = "llama3:8b"
        const val LLAMA_3_70B = "llama3:70b"
        const val LLAMA_2_7B = "llama2:7b"
        const val LLAMA_2_13B = "llama2:13b"
        const val LLAMA_2_70B = "llama2:70b"

        // Code-specialized Models
        const val CODELLAMA_7B = "codellama:7b"
        const val CODELLAMA_13B = "codellama:13b"
        const val CODELLAMA_34B = "codellama:34b"
        const val CODEGEMMA_2B = "codegemma:2b"
        const val CODEGEMMA_7B = "codegemma:7b"

        // Gemma Models
        const val GEMMA_2B = "gemma:2b"
        const val GEMMA_7B = "gemma:7b"
        const val GEMMA2_9B = "gemma2:9b"
        const val GEMMA2_27B = "gemma2:27b"

        // Mistral Models
        const val MISTRAL_7B = "mistral:7b"
        const val MIXTRAL_8X7B = "mixtral:8x7b"
        const val MIXTRAL_8X22B = "mixtral:8x22b"

        // Specialized Models
        const val QWEN2_0_5B = "qwen2:0.5b"
        const val QWEN2_1_5B = "qwen2:1.5b"
        const val QWEN2_7B = "qwen2:7b"
        const val QWEN2_72B = "qwen2:72b"
        const val PHI3_MINI = "phi3:mini"
        const val PHI3_MEDIUM = "phi3:medium"
        const val NEURAL_CHAT_7B = "neural-chat:7b"
        const val ORCA_MINI_3B = "orca-mini:3b"
        const val VICUNA_7B = "vicuna:7b"
        const val VICUNA_13B = "vicuna:13b"

        // Embedding Models
        const val NOMIC_EMBED_TEXT = "nomic-embed-text"
        const val ALL_MINILM = "all-minilm"

        // Vision Models
        const val LLAVA_7B = "llava:7b"
        const val LLAVA_13B = "llava:13b"
        const val LLAVA_34B = "llava:34b"

    }
}
