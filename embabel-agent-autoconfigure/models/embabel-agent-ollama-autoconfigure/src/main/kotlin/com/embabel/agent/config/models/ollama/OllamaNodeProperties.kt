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
package com.embabel.agent.config.models.ollama

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for Ollama multi-node setup.
 *
 * Supports application-driven configuration for multiple Ollama instances:
 * - spring.ai.ollama.nodes[0].name=main
 * - spring.ai.ollama.nodes[0].base-url=http://localhost:11434
 * - spring.ai.ollama.nodes[1].name=gpu-server
 * - spring.ai.ollama.nodes[1].base-url=http://localhost:11435
 */
@ConfigurationProperties(prefix = "spring.ai.ollama")
data class OllamaNodeProperties(
    /**
     * List of Ollama nodes for explicit multi-instance access.
     * When empty, library uses only the default base-url configuration.
     */
    var nodes: List<OllamaNodeConfig> = emptyList()
)

/**
 * Configuration for individual Ollama node
 */
data class OllamaNodeConfig(
    /**
     * Logical name of the node for explicit access via ollamaModel-{nodeName}-{modelName}
     */
    var name: String = "",

    /**
     * Base URL for this specific node (e.g., http://localhost:11435)
     */
    var baseUrl: String = ""
)
