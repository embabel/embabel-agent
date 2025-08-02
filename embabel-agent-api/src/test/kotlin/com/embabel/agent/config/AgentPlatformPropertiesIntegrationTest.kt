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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

/**
 * Tests for AgentPlatformProperties configuration binding and property resolution precedence.
 *
 * ## Property Resolution Strategy
 *
 * This test validates Spring Boot's `@ConfigurationProperties` binding mechanism for platform
 * configuration properties. The test intentionally **does NOT use** the `agent-platform.properties`
 * file to ensure binding works independently of any property file.
 *
 * ## TestPropertySource Override Behavior
 *
 * The `@TestPropertySource` annotation provides **higher precedence** than property files in
 * Spring's property resolution order:
 *
 * ```
 * 1. @TestPropertySource (HIGHEST) → Test values used
 * 2. System Properties
 * 3. application.properties
 * 4. agent-platform.properties (IGNORED in tests)
 * 5. Kotlin class defaults (FALLBACK)
 * ```
 *
 * ## Test Design Rationale
 *
 * - **Complete Override**: Test defines custom values for every property to verify binding
 * - **Different Values**: Test values intentionally differ from both `agent-platform.properties`
 *   defaults and Kotlin class defaults to prove override mechanism works
 * - **Independent Validation**: Tests work without any property files, ensuring robustness
 * - **Default Testing**: Separate test validates Kotlin class defaults work as fallback
 *
 * ## Examples
 *
 * - `agent-platform.properties`: `ranking.max-attempts=5` (production default)
 * - `@TestPropertySource`: `ranking.max-attempts=15` (test override) ← **WINS**
 * - `RankingConfig()`: `maxAttempts=5` (Kotlin fallback)
 *
 * This ensures the configuration system works correctly across all deployment scenarios.
 */
@SpringBootTest(classes = [AgentPlatformPropertiesIntegrationTest.TestConfiguration::class])
@TestPropertySource(properties = [
    "embabel.agent.platform.name=test-platform",
    "embabel.agent.platform.description=Test Platform Description",
    "embabel.agent.platform.scanning.annotation=false",
    "embabel.agent.platform.scanning.bean=true",
    "embabel.agent.platform.ranking.max-attempts=15",
    "embabel.agent.platform.ranking.backoff-millis=200",
    "embabel.agent.platform.autonomy.agent-confidence-cut-off=0.8",
    "embabel.agent.platform.autonomy.goal-confidence-cut-off=0.7",
    "embabel.agent.platform.process-id-generation.include-version=true",
    "embabel.agent.platform.process-id-generation.include-agent-name=true",
    "embabel.agent.platform.llm-operations.prompts.maybe-prompt-template=custom_template",
    "embabel.agent.platform.llm-operations.prompts.generate-examples-by-default=false",
    "embabel.agent.platform.llm-operations.data-binding.max-attempts=20",
    "embabel.agent.platform.llm-operations.data-binding.fixed-backoff-millis=50",
    "embabel.agent.platform.models.anthropic.max-attempts=8",
    "embabel.agent.platform.models.anthropic.backoff-millis=3000",
    "embabel.agent.platform.models.openai.max-attempts=12",
    "embabel.agent.platform.models.openai.backoff-millis=2500",
    "embabel.agent.platform.sse.max-buffer-size=200",
    "embabel.agent.platform.sse.max-process-buffers=2000",
    "embabel.agent.platform.test.mock-mode=false"
])
class AgentPlatformPropertiesIntegrationTest {

    @Autowired
    private lateinit var properties: AgentPlatformProperties

    @Test
    fun `should bind core platform properties correctly`() {
        assertThat(properties.name).isEqualTo("test-platform")
        assertThat(properties.description).isEqualTo("Test Platform Description")
    }

    @Test
    fun `should bind scanning properties correctly`() {
        assertThat(properties.scanning.annotation).isFalse()
        assertThat(properties.scanning.bean).isTrue()
    }

    @Test
    fun `should bind ranking properties correctly`() {
        assertThat(properties.ranking.maxAttempts).isEqualTo(15)
        assertThat(properties.ranking.backoffMillis).isEqualTo(200L)
    }

    @Test
    fun `should bind autonomy properties correctly`() {
        assertThat(properties.autonomy.agentConfidenceCutOff).isEqualTo(0.8)
        assertThat(properties.autonomy.goalConfidenceCutOff).isEqualTo(0.7)
    }

    @Test
    fun `should bind process ID generation properties correctly`() {
        assertThat(properties.processIdGeneration.includeVersion).isTrue()
        assertThat(properties.processIdGeneration.includeAgentName).isTrue()
    }

    @Test
    fun `should bind LLM operations properties correctly`() {
        assertThat(properties.llmOperations.prompts.maybePromptTemplate).isEqualTo("custom_template")
        assertThat(properties.llmOperations.prompts.generateExamplesByDefault).isFalse()
        assertThat(properties.llmOperations.dataBinding.maxAttempts).isEqualTo(20)
        assertThat(properties.llmOperations.dataBinding.fixedBackoffMillis).isEqualTo(50L)
    }

    @Test
    fun `should bind model provider properties correctly`() {
        assertThat(properties.models.anthropic.maxAttempts).isEqualTo(8)
        assertThat(properties.models.anthropic.backoffMillis).isEqualTo(3000L)
        assertThat(properties.models.openai.maxAttempts).isEqualTo(12)
        assertThat(properties.models.openai.backoffMillis).isEqualTo(2500L)
    }

    @Test
    fun `should bind SSE properties correctly`() {
        assertThat(properties.sse.maxBufferSize).isEqualTo(200)
        assertThat(properties.sse.maxProcessBuffers).isEqualTo(2000)
    }

    @Test
    fun `should bind test properties correctly`() {
        assertThat(properties.test.mockMode).isFalse()
    }

    @Test
    fun `should use default values when properties not specified`() {
        val defaultProperties = AgentPlatformProperties()

        // Test a few key defaults
        assertThat(defaultProperties.scanning.annotation).isTrue()
        assertThat(defaultProperties.ranking.maxAttempts).isEqualTo(5)
        assertThat(defaultProperties.autonomy.agentConfidenceCutOff).isEqualTo(0.6)
        assertThat(defaultProperties.models.anthropic.maxAttempts).isEqualTo(10)
        assertThat(defaultProperties.models.openai.maxAttempts).isEqualTo(10)
        assertThat(defaultProperties.test.mockMode).isTrue()
    }

    @EnableConfigurationProperties(AgentPlatformProperties::class)
    class TestConfiguration
}
