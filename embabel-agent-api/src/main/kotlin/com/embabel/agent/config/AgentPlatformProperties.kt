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
     * Core platform identity name
     */
    var name: String = "embabel-default"

    /**
     * Platform description
     */
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
        /**
         *  Whether to auto register beans with @Agent and @Agentic annotation
         */
        var annotation: Boolean = true

        /**
         * Whether to auto register as agents Spring beans of type Agent
         */
        var bean: Boolean = false
    }

    /**
     * Ranking configuration with retry logic
     */
    class RankingConfig {
        /**
         * Name of the LLM to use for ranking, or null to use auto selection
         */
        var llm: String? = null

        /**
         * Maximum number of attempts to retry ranking
         */
        var maxAttempts: Int = 5

        /**
         * Initial backoff time in milliseconds
         */
        var backoffMillis: Long = 100L

        /**
         * Multiplier for backoff time
         */
        var backoffMultiplier: Double = 5.0

        /**
         * Maximum backoff time in milliseconds
         */
        var backoffMaxInterval: Long = 180000L
    }

    /**
     * LLM operations configuration
     */
    @ConfigurationProperties(prefix = "embabel.agent.platform.llm-operations")
    class LlmOperationsConfig {
        @field:NestedConfigurationProperty
        var prompts: PromptsConfig = PromptsConfig()

        @field:NestedConfigurationProperty
        var dataBinding: DataBindingConfig = DataBindingConfig()

        /**
         * Prompt configuration
         */
        class PromptsConfig {
            /**
             * Template for "maybe" prompt, enabling failure result when LLM lacks information
             */
            var maybePromptTemplate: String = "maybe_prompt_contribution"

            /**
             * Whether to generate examples by default
             */
            var generateExamplesByDefault: Boolean = true
        }

        /**
         * Data binding retry configuration
         */
        class DataBindingConfig {
            /**
             * Maximum retry attempts for data binding
             */
            var maxAttempts: Int = 10

            /**
             * Fixed backoff time in milliseconds between retries
             */
            var fixedBackoffMillis: Long = 30L
        }
    }

    /**
     * Process ID generation configuration
     */
    @ConfigurationProperties("embabel.agent.platform.process-id-generation")
    class ProcessIdGenerationConfig {
        /**
         * Whether to include version in process ID generation
         */
        var includeVersion: Boolean = false

        /**
         * Whether to include agent name in process ID generation
         */
        var includeAgentName: Boolean = false
    }

    /**
     * Autonomy thresholds configuration
     */
    @ConfigurationProperties("embabel.agent.platform.autonomy")
    class AutonomyConfig {
        /**
         * Confidence threshold for agent operations
         */
        var agentConfidenceCutOff: Double = 0.6

        /**
         * Confidence threshold for goal achievement
         */
        var goalConfidenceCutOff: Double = 0.6
    }

    /**
     * Model provider integration configurations
     */
    @ConfigurationProperties("embabel.agent.platform.models")
    class ModelsConfig {
        @field:NestedConfigurationProperty
        var anthropic: AnthropicConfig = AnthropicConfig()

        @field:NestedConfigurationProperty
        var openai: OpenAiConfig = OpenAiConfig()

        /**
         * Anthropic provider retry configuration
         */
        class AnthropicConfig {
            /**
             * Maximum retry attempts
             */
            var maxAttempts: Int = 10

            /**
             * Initial backoff time in milliseconds
             */
            var backoffMillis: Long = 5000L

            /**
             * Backoff multiplier
             */
            var backoffMultiplier: Double = 5.0

            /**
             * Maximum backoff interval in milliseconds
             */
            var backoffMaxInterval: Long = 180000L
        }

        /**
         * OpenAI provider retry configuration
         */
        class OpenAiConfig {
            /**
             * Maximum retry attempts
             */
            var maxAttempts: Int = 10

            /**
             * Initial backoff time in milliseconds
             */
            var backoffMillis: Long = 5000L

            /**
             * Backoff multiplier
             */
            var backoffMultiplier: Double = 5.0

            /**
             * Maximum backoff interval in milliseconds
             */
            var backoffMaxInterval: Long = 180000L
        }
    }

    /**
     * Server-sent events configuration
     */
    @ConfigurationProperties("embabel.agent.platform.sse")
    class SseConfig {
        /**
         * Maximum buffer size for SSE
         */
        var maxBufferSize: Int = 100

        /**
         * Maximum number of process buffers
         */
        var maxProcessBuffers: Int = 1000
    }

    /**
     * Test configuration
     */
    @ConfigurationProperties("embabel.agent.platform.test")
    class TestConfig {
        /**
         * Whether to enable mock mode for testing
         */
        var mockMode: Boolean = true
    }
}
