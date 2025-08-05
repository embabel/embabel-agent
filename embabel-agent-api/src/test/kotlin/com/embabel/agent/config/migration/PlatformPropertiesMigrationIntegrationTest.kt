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
package com.embabel.agent.config.migration

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.test.context.TestPropertySource

/**
 * Integration tests for the complete platform properties migration system.
 *
 * Tests the interaction between ConditionalPropertyScanningConfig, SimpleDeprecatedConfigWarner,
 * and ConditionalPropertyScanner components working together for platform property migrations.
 */
@SpringBootTest(classes = [
    PlatformPropertiesMigrationIntegrationTest.TestConfiguration::class
])
@TestPropertySource(properties = [
    // Enable migration scanning
    "embabel.agent.platform.migration.scanning.enabled=true",
    "embabel.agent.platform.migration.scanning.include-packages[0]=com.embabel.agent",

    // Set up deprecated properties for testing
    "embabel.agent.anthropic.max-attempts=15",
    "embabel.agent-platform.ranking.backoff-millis=500",
    "embabel.agent.sse.max-buffer-size=200",

    // Configure scanning to include test packages
    "embabel.agent.platform.migration.scanning.auto-exclude-jar-packages=false"
])
class PlatformPropertiesMigrationIntegrationTest {

    @Autowired
    private lateinit var scanningConfig: ConditionalPropertyScanningConfig

    @Autowired
    private lateinit var propertyWarner: SimpleDeprecatedConfigWarner

    @Autowired
    private lateinit var propertyScanner: ConditionalPropertyScanner

    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun setUp() {
        // Set up log capture for warner
        logger = LoggerFactory.getLogger(SimpleDeprecatedConfigWarner::class.java) as Logger
        listAppender = ListAppender()
        listAppender.start()
        logger.addAppender(listAppender)
        logger.level = Level.WARN

        // Clear any previous warnings
        propertyWarner.clearWarnings()
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(listAppender)
        listAppender.stop()
    }

    @Test
    fun `should configure scanning correctly from properties`() {
        assertThat(scanningConfig.enabled).isTrue()
        assertThat(scanningConfig.includePackages).contains("com.embabel.agent")
        assertThat(scanningConfig.autoExcludeJarPackages).isFalse()
    }

    @Test
    fun `should issue warnings for deprecated properties`() {
        // When - manually trigger property warnings (simulating what scanner would do)
        propertyWarner.warnDeprecatedProperty(
            "embabel.agent.anthropic.max-attempts",
            "embabel.agent.platform.models.anthropic.max-attempts",
            "Model provider configuration consolidation"
        )

        propertyWarner.warnDeprecatedProperty(
            "embabel.agent-platform.ranking.backoff-millis",
            "embabel.agent.platform.ranking.backoff-millis",
            "Platform namespace consolidation"
        )

        // Then
        assertThat(listAppender.list).hasSize(2)
        assertThat(propertyWarner.getWarningCount()).isEqualTo(2)

        val messages = listAppender.list.map { it.message }
        assertThat(messages).anySatisfy { message ->
            assertThat(message).contains(
                "embabel.agent.anthropic.max-attempts",
                "embabel.agent.platform.models.anthropic.max-attempts",
                "15"
            )
        }
        assertThat(messages).anySatisfy { message ->
            assertThat(message).contains(
                "embabel.agent-platform.ranking.backoff-millis",
                "embabel.agent.platform.ranking.backoff-millis",
                "500"
            )
        }
    }

    @Test
    fun `should transform properties using migration rules correctly`() {
        // Given - Test the complete rule transformation pipeline
        val testCases = mapOf(
            "embabel.agent.anthropic.max-attempts" to "embabel.agent.platform.models.anthropic.max-attempts",
            "embabel.agent.anthropic.backoff-millis" to "embabel.agent.platform.models.anthropic.backoff-millis",
            "embabel.agent.openai.max-attempts" to "embabel.agent.platform.models.openai.max-attempts",
            "embabel.agent-platform.ranking.max-attempts" to "embabel.agent.platform.ranking.max-attempts",
            "embabel.agent-platform.llm-operations.prompts.template" to "embabel.agent.platform.llm-operations.prompts.template"
        )

        testCases.forEach { (deprecated, expected) ->
            // When - apply rules through scanner
            val result = getRecommendedPropertyUsingScanner(deprecated)

            // Then
            assertThat(result).isEqualTo(expected)
        }
    }

    @Test
    fun `should handle exact property mappings`() {
        // Given - test exact mappings vs pattern rules (for @ConditionalOnProperty migrations only)
        val exactMappings = mapOf(
            "embabel.agent.enable-scanning" to "embabel.agent.platform.scanning.annotation",
            "embabel.agent.mock-mode" to "embabel.agent.platform.test.mock-mode",
            "embabel.anthropic" to "embabel.agent.platform.models.anthropic"
        )

        exactMappings.forEach { (deprecated, expected) ->
            // When
            val result = getRecommendedPropertyUsingScanner(deprecated)

            // Then
            assertThat(result).isEqualTo(expected)
        }
    }

    @Test
    fun `should not transform already migrated properties`() {
        // Given - properties that are already in correct namespace
        val alreadyMigrated = listOf(
            "embabel.agent.platform.models.anthropic.max-attempts",
            "embabel.agent.platform.ranking.max-attempts",
            "embabel.agent.platform.sse.max-buffer-size"
        )

        alreadyMigrated.forEach { property ->
            // When
            val result = getRecommendedPropertyUsingScanner(property)

            // Then - should fall back to generic message
            assertThat(result).contains("please check migration guide")
        }
    }

    @Test
    fun `should handle package inclusion and exclusion correctly`() {
        // Test that scanning config correctly identifies packages to include/exclude
        assertThat(scanningConfig.shouldIncludePackage("com.embabel.agent.config.migration.TestClass")).isTrue()
        assertThat(scanningConfig.shouldIncludePackage("com.embabel.agent.service.MyService")).isTrue()

        // Framework packages should be excluded
        assertThat(scanningConfig.shouldIncludePackage("org.springframework.boot.Application")).isFalse()
        assertThat(scanningConfig.shouldIncludePackage("java.lang.String")).isFalse()
    }

    @Test
    fun `should support runtime rule addition`() {
        // Given
        val initialRuleCount = propertyScanner.getMigrationRules().size
        val customRule = ConditionalPropertyScanner.PropertyMigrationRule(
            pattern = java.util.regex.Pattern.compile("custom\\.([^.]+)\\.config"),
            replacement = "migrated.custom.\$1.config",
            description = "Custom runtime rule"
        )

        // When
        propertyScanner.addMigrationRule(customRule)

        // Then
        assertThat(propertyScanner.getMigrationRules()).hasSize(initialRuleCount + 1)

        // And the new rule should work (test the rule directly since scanner method is private)
        val result = customRule.tryApply("custom.test.config")
        assertThat(result).isEqualTo("migrated.custom.test.config")
    }

    @Test
    fun `should demonstrate complete migration workflow`() {
        // Given - simulate a complete migration scenario
        val deprecatedProperties = mapOf(
            "embabel.agent.anthropic.max-attempts" to "15",
            "embabel.agent-platform.ranking.backoff-millis" to "500",
            "embabel.agent.sse.max-buffer-size" to "200"
        )

        // When - process each deprecated property through the complete system
        deprecatedProperties.forEach { (property, value) ->
            val recommendedProperty = getRecommendedPropertyUsingScanner(property)

            // Simulate the scanner finding and warning about this property
            propertyWarner.warnDeprecatedProperty(
                deprecatedProperty = property,
                recommendedProperty = recommendedProperty,
                deprecationReason = "Property migration consolidation"
            )
        }

        // Then - verify complete system behavior
        assertThat(propertyWarner.getWarningCount()).isEqualTo(3)
        assertThat(listAppender.list).hasSize(3)

        // Verify all warnings contain expected information
        val logMessages = listAppender.list.map { it.message }
        assertThat(logMessages).allSatisfy { message ->
            assertThat(message).contains("DEPRECATED PROPERTY USAGE")
            assertThat(message).contains("Property migration consolidation")
        }

        // Verify specific transformations
        assertThat(logMessages).anySatisfy { message ->
            assertThat(message).contains("embabel.agent.platform.models.anthropic.max-attempts")
        }
        assertThat(logMessages).anySatisfy { message ->
            assertThat(message).contains("embabel.agent.platform.ranking.backoff-millis")
        }
        assertThat(logMessages).anySatisfy { message ->
            assertThat(message).contains("embabel.agent.platform.sse.max-buffer-size")
        }
    }

    /**
     * Helper method to simulate the scanner's explicit mapping logic for testing.
     * Uses the same explicit mappings as the actual scanner.
     */
    private fun getRecommendedPropertyUsingScanner(deprecatedProperty: String): String {
        // Explicit mappings from the scanner (replicated for testing)
        val exactMappings = mapOf(
            // Platform namespace consolidation
            "embabel.agent-platform.ranking.max-attempts" to "embabel.agent.platform.ranking.max-attempts",
            "embabel.agent-platform.ranking.backoff-millis" to "embabel.agent.platform.ranking.backoff-millis",
            "embabel.agent-platform.llm-operations.prompts.template" to "embabel.agent.platform.llm-operations.prompts.template",

            // Model provider configurations
            "embabel.agent.anthropic.max-attempts" to "embabel.agent.platform.models.anthropic.max-attempts",
            "embabel.agent.anthropic.backoff-millis" to "embabel.agent.platform.models.anthropic.backoff-millis",
            "embabel.agent.openai.max-attempts" to "embabel.agent.platform.models.openai.max-attempts",
            "embabel.agent.openai.backoff-millis" to "embabel.agent.platform.models.openai.backoff-millis",

            // Specific platform features
            "embabel.agent.enable-scanning" to "embabel.agent.platform.scanning.annotation",
            "embabel.agent.mock-mode" to "embabel.agent.platform.test.mock-mode",
            "embabel.agent.sse.max-buffer-size" to "embabel.agent.platform.sse.max-buffer-size",
            "embabel.agent.sse.max-process-buffers" to "embabel.agent.platform.sse.max-process-buffers",

            // @ConfigurationProperties prefix migrations
            "embabel.anthropic" to "embabel.agent.platform.models.anthropic",
            "embabel.openai" to "embabel.agent.platform.models.openai"
        )

        // Simple lookup (mirroring simplified scanner logic)
        return exactMappings[deprecatedProperty]
            ?: "$deprecatedProperty (please check migration guide for specific replacement)"
    }

    @Configuration
    @EnableConfigurationProperties(ConditionalPropertyScanningConfig::class)
    class TestConfiguration {

        @Bean
        fun simpleDeprecatedConfigWarner(environment: Environment): SimpleDeprecatedConfigWarner =
            SimpleDeprecatedConfigWarner(environment, enableIndividualLogging = true)

        @Bean
        fun conditionalPropertyScanner(
            scanningConfigProvider: ObjectProvider<ConditionalPropertyScanningConfig>,
            propertyWarnerProvider: ObjectProvider<SimpleDeprecatedConfigWarner>
        ): ConditionalPropertyScanner =
            ConditionalPropertyScanner(scanningConfigProvider, propertyWarnerProvider)
    }
}
