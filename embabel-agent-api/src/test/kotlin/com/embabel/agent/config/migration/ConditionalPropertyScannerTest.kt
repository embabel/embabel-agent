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

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.*
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory
import java.util.regex.Pattern

/**
 * Tests for ConditionalPropertyScanner explicit mapping and runtime rule functionality.
 */
class ConditionalPropertyScannerTest {

    private lateinit var scanningConfig: ConditionalPropertyScanningConfig
    private lateinit var propertyWarner: SimpleDeprecatedConfigWarner
    private lateinit var scanner: ConditionalPropertyScanner
    private lateinit var beanFactory: ConfigurableListableBeanFactory

    @BeforeEach
    fun setUp() {
        scanningConfig = mock(ConditionalPropertyScanningConfig::class.java)
        propertyWarner = mock(SimpleDeprecatedConfigWarner::class.java)
        scanner = ConditionalPropertyScanner(scanningConfig, propertyWarner)
        beanFactory = mock(ConfigurableListableBeanFactory::class.java)
    }

    @Test
    fun `scanner should skip processing when disabled`() {
        // Given
        `when`(scanningConfig.enabled).thenReturn(false)

        // When
        scanner.postProcessBeanFactory(beanFactory)

        // Then
        verify(scanningConfig, times(1)).enabled
        verifyNoMoreInteractions(scanningConfig)
        verifyNoInteractions(propertyWarner)
    }

    @Test
    fun `scanner should process when enabled`() {
        // Given
        `when`(scanningConfig.enabled).thenReturn(true)
        `when`(scanningConfig.includePackages).thenReturn(listOf("com.example.test"))

        // When
        scanner.postProcessBeanFactory(beanFactory)

        // Then
        verify(scanningConfig).enabled
        verify(scanningConfig, atLeastOnce()).includePackages
        // Note: Full scanning behavior would require more complex mocking of Spring's resource resolution
    }

    @Test
    fun `getMigrationRules should return empty list initially`() {
        // When - No pattern rules are configured by default (using explicit mappings)
        val rules = scanner.getMigrationRules()

        // Then
        assertThat(rules).isEmpty()
    }

    @Test
    fun `addMigrationRule should allow runtime rule addition`() {
        // Given
        val initialRuleCount = scanner.getMigrationRules().size
        val newRule = ConditionalPropertyScanner.PropertyMigrationRule(
            pattern = Pattern.compile("test\\.([^.]+)\\.property"),
            replacement = "migrated.test.\$1.property",
            description = "Test rule addition"
        )

        // When
        scanner.addMigrationRule(newRule)

        // Then
        val updatedRules = scanner.getMigrationRules()
        assertThat(updatedRules).hasSize(initialRuleCount + 1)
        assertThat(updatedRules.last().description).isEqualTo("Test rule addition")
    }

    @Test
    fun `PropertyMigrationRule should transform properties correctly when used`() {
        // Given - Test pattern-based rules for runtime extensibility
        val rule = ConditionalPropertyScanner.PropertyMigrationRule(
            pattern = Pattern.compile("custom\\.company\\.([^.]+)\\.config"),
            replacement = "embabel.agent.custom.\$1.config",
            description = "Custom company namespace migration"
        )

        // When/Then - Pattern transformation
        assertThat(rule.tryApply("custom.company.auth.config"))
            .isEqualTo("embabel.agent.custom.auth.config")

        assertThat(rule.tryApply("custom.company.database.config"))
            .isEqualTo("embabel.agent.custom.database.config")

        // When/Then - Non-matching property
        assertThat(rule.tryApply("other.namespace.auth.config"))
            .isNull()
    }

    @Test
    fun `PropertyMigrationRule should respect conditions`() {
        // Given
        val conditionalRule = ConditionalPropertyScanner.PropertyMigrationRule(
            pattern = Pattern.compile("legacy\\.([^.]+)\\.setting"),
            replacement = "embabel.agent.legacy.\$1.setting",
            description = "Test rule with condition",
            condition = { property -> property.contains("important") }
        )

        // When/Then - Condition met
        assertThat(conditionalRule.tryApply("legacy.important.setting"))
            .isEqualTo("embabel.agent.legacy.important.setting")

        // When/Then - Condition not met
        assertThat(conditionalRule.tryApply("legacy.optional.setting"))
            .isNull()
    }

    @Test
    fun `runtime rules should be invoked after explicit mappings`() {
        // Given - Add a runtime rule
        val runtimeRule = ConditionalPropertyScanner.PropertyMigrationRule(
            pattern = Pattern.compile("runtime\\.([^.]+)\\.test"),
            replacement = "embabel.agent.runtime.\$1.test",
            description = "Runtime extensibility test"
        )
        scanner.addMigrationRule(runtimeRule)

        // When/Then - Runtime rule should work for unmapped properties
        // Note: This would be tested through integration tests since the method is private
        assertThat(runtimeRule.tryApply("runtime.custom.test"))
            .isEqualTo("embabel.agent.runtime.custom.test")
    }

    @Test
    fun `PropertyMigrationRule should handle edge cases`() {
        // Given
        val rule = ConditionalPropertyScanner.PropertyMigrationRule(
            pattern = Pattern.compile("test\\.(.+)"),
            replacement = "migrated.\$1",
            description = "Test rule"
        )

        // When/Then - Empty capture group (note: .+ requires at least one character)
        assertThat(rule.tryApply("test."))
            .isNull()

        // When/Then - Multiple dots in capture
        assertThat(rule.tryApply("test.very.deep.property"))
            .isEqualTo("migrated.very.deep.property")

        // When/Then - No match
        assertThat(rule.tryApply("other.property"))
            .isNull()
    }

    @Test
    fun `rule patterns should be correctly escaped for regex`() {
        // Given
        val dotSensitiveRule = ConditionalPropertyScanner.PropertyMigrationRule(
            pattern = Pattern.compile("exact\\.match\\.test"),
            replacement = "migrated.exact.match.test",
            description = "Dot escaping test"
        )

        // When/Then - Verify dot escaping works correctly
        assertThat(dotSensitiveRule.tryApply("exact.match.test"))
            .isEqualTo("migrated.exact.match.test")

        // Should not match similar but different patterns (dots are literal)
        assertThat(dotSensitiveRule.tryApply("exactXmatchXtest"))
            .isNull()
    }

    @Test
    fun `multiple runtime rules should be processed in order`() {
        // Given
        val rule1 = ConditionalPropertyScanner.PropertyMigrationRule(
            pattern = Pattern.compile("first\\.(.+)"),
            replacement = "migrated.first.\$1",
            description = "First rule"
        )

        val rule2 = ConditionalPropertyScanner.PropertyMigrationRule(
            pattern = Pattern.compile("second\\.(.+)"),
            replacement = "migrated.second.\$1",
            description = "Second rule"
        )

        // When
        scanner.addMigrationRule(rule1)
        scanner.addMigrationRule(rule2)

        // Then
        val rules = scanner.getMigrationRules()
        assertThat(rules).hasSize(2)
        assertThat(rules[0].description).isEqualTo("First rule")
        assertThat(rules[1].description).isEqualTo("Second rule")
    }
}
