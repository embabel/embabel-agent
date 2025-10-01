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
import org.springframework.boot.context.properties.NestedConfigurationProperty

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
class AgentPlatformProperties {
    /**
     * Core platform identity
     */
    var name: String = "embabel-default"
    var description: String = "Embabel Default Agent Platform"

    /**
     * Platform behavior configurations
     */
    @field:NestedConfigurationProperty
    var scanning: ScanningConfig = ScanningConfig()
    @field:NestedConfigurationProperty
    var ranking: RankingConfig = RankingConfig()
    @field:NestedConfigurationProperty
    var llmOperations: LlmOperationsConfig = LlmOperationsConfig()
    @field:NestedConfigurationProperty
    var processIdGeneration: ProcessIdGenerationConfig = ProcessIdGenerationConfig()
    @field:NestedConfigurationProperty
    var autonomy: AutonomyConfig = AutonomyConfig()
    @field:NestedConfigurationProperty
    var models: ModelsConfig = ModelsConfig()
    @field:NestedConfigurationProperty
    var sse: SseConfig = SseConfig()
    @field:NestedConfigurationProperty
    var test: TestConfig = TestConfig()

    /**
     * Agent scanning configuration
     */
    class ScanningConfig {
        var annotation: Boolean = true
        var bean: Boolean = false
    }

    /**
     * Ranking configuration with retry logic
     */
    class RankingConfig {
        var llm: String? = null
        var maxAttempts: Int = 5
        var backoffMillis: Long = 100L
        var backoffMultiplier: Double = 5.0
        var backoffMaxInterval: Long = 180000L
    }

    /**
     * LLM operations configuration
     */
    class LlmOperationsConfig {
        @field:NestedConfigurationProperty
        var prompts: PromptsConfig = PromptsConfig()
        @field:NestedConfigurationProperty
        var dataBinding: DataBindingConfig = DataBindingConfig()

        /**
         * Prompt configuration
         */
        class PromptsConfig {
            var maybePromptTemplate: String = "maybe_prompt_contribution"
            var generateExamplesByDefault: Boolean = true
        }

        /**
         * Data binding retry configuration
         */
        class DataBindingConfig {
            var maxAttempts: Int = 10
            var fixedBackoffMillis: Long = 30L
        }
    }

    /**
     * Process ID generation configuration
     */
    class ProcessIdGenerationConfig {
        var includeVersion: Boolean = false
        var includeAgentName: Boolean = false
    }

    /**
     * Autonomy thresholds configuration
     */
    class AutonomyConfig {
        var agentConfidenceCutOff: Double = 0.6
        var goalConfidenceCutOff: Double = 0.6
    }

    /**
     * Model provider integration configurations
     */
    class ModelsConfig {
        @field:NestedConfigurationProperty
        var anthropic: AnthropicConfig = AnthropicConfig()
        @field:NestedConfigurationProperty
        var openai: OpenAiConfig = OpenAiConfig()

        /**
         * Anthropic provider retry configuration
         */
        class AnthropicConfig {
            var maxAttempts: Int = 10
            var backoffMillis: Long = 5000L
            var backoffMultiplier: Double = 5.0
            var backoffMaxInterval: Long = 180000L
        }

        /**
         * OpenAI provider retry configuration
         */
        class OpenAiConfig {
            var maxAttempts: Int = 10
            var backoffMillis: Long = 5000L
            var backoffMultiplier: Double = 5.0
            var backoffMaxInterval: Long = 180000L
        }
    }

    /**
     * Server-sent events configuration
     */
    class SseConfig {
        var maxBufferSize: Int = 100
        var maxProcessBuffers: Int = 1000
    }

    /**
     * Test configuration
     */
    class TestConfig {
        var mockMode: Boolean = true
    }
}
