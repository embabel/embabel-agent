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
package com.embabel.agent.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Unified configuration for all agent platform properties.
 *
 * These properties control internal platform behavior and are rarely customized by users.
 * Platform properties are segregated from application properties to clearly separate
 * framework internals from business logic configuration.
 *
 * @since 1.x
 */
@ConfigurationProperties("embabel.agent.platform")
data class AgentPlatformProperties(
    /**
     * Core platform identity
     */
    val name: String = "embabel-default",
    val description: String = "Embabel Default Agent Platform",

    /**
     * Platform behavior configurations
     */
    val scanning: ScanningConfig = ScanningConfig(),
    val ranking: RankingConfig = RankingConfig(),
    val llmOperations: LlmOperationsConfig = LlmOperationsConfig(),
    val processIdGeneration: ProcessIdGenerationConfig = ProcessIdGenerationConfig(),
    val autonomy: AutonomyConfig = AutonomyConfig(),
    val models: ModelsConfig = ModelsConfig(),
    val sse: SseConfig = SseConfig(),
    val test: TestConfig = TestConfig()
) {
    /**
     * Agent scanning configuration
     */
    data class ScanningConfig(
        val annotation: Boolean = true,
        val bean: Boolean = false
    )

    /**
     * Ranking configuration with retry logic
     */
    data class RankingConfig(
        val llm: String? = null,
        val maxAttempts: Int = 5,
        val backoffMillis: Long = 100L,
        val backoffMultiplier: Double = 5.0,
        val backoffMaxInterval: Long = 180000L
    )

    /**
     * LLM operations configuration
     */
    data class LlmOperationsConfig(
        val prompts: PromptsConfig = PromptsConfig(),
        val dataBinding: DataBindingConfig = DataBindingConfig()
    ) {
        /**
         * Prompt configuration
         */
        data class PromptsConfig(
            val maybePromptTemplate: String = "maybe_prompt_contribution",
            val generateExamplesByDefault: Boolean = true
        )

        /**
         * Data binding retry configuration
         */
        data class DataBindingConfig(
            val maxAttempts: Int = 10,
            val fixedBackoffMillis: Long = 30L
        )
    }

    /**
     * Process ID generation configuration
     */
    data class ProcessIdGenerationConfig(
        val includeVersion: Boolean = false,
        val includeAgentName: Boolean = false
    )

    /**
     * Autonomy thresholds configuration
     */
    data class AutonomyConfig(
        val agentConfidenceCutOff: Double = 0.6,
        val goalConfidenceCutOff: Double = 0.6
    )

    /**
     * Model provider integration configurations
     */
    data class ModelsConfig(
        val anthropic: AnthropicConfig = AnthropicConfig(),
        val openai: OpenAiConfig = OpenAiConfig()
    ) {
        /**
         * Anthropic provider retry configuration
         */
        data class AnthropicConfig(
            val maxAttempts: Int = 10,
            val backoffMillis: Long = 5000L,
            val backoffMultiplier: Double = 5.0,
            val backoffMaxInterval: Long = 180000L
        )

        /**
         * OpenAI provider retry configuration
         */
        data class OpenAiConfig(
            val maxAttempts: Int = 10,
            val backoffMillis: Long = 5000L,
            val backoffMultiplier: Double = 5.0,
            val backoffMaxInterval: Long = 180000L
        )
    }

    /**
     * Server-sent events configuration
     */
    data class SseConfig(
        val maxBufferSize: Int = 100,
        val maxProcessBuffers: Int = 1000
    )

    /**
     * Test configuration
     */
    data class TestConfig(
        val mockMode: Boolean = true
    )
}
