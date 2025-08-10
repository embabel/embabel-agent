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
import io.mockk.*
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mock.env.MockEnvironment
import java.util.regex.Pattern

/**
 * Tests for DeprecatedPropertyScanner explicit mapping and runtime rule functionality.
 */
class DeprecatedPropertyScannerTest {

    private lateinit var scanningConfig: DeprecatedPropertyScanningConfig
    private lateinit var propertyWarner: SimpleDeprecatedConfigWarner
    private lateinit var scanner: DeprecatedPropertyScanner
    private lateinit var scanningConfigProvider: ObjectProvider<DeprecatedPropertyScanningConfig>
    private lateinit var propertyWarnerProvider: ObjectProvider<SimpleDeprecatedConfigWarner>
    private lateinit var environment: MockEnvironment

    @BeforeEach
    fun setUp() {
        scanningConfig = mockk<DeprecatedPropertyScanningConfig>()
        propertyWarner = mockk<SimpleDeprecatedConfigWarner>()
        scanningConfigProvider = mockk<ObjectProvider<DeprecatedPropertyScanningConfig>>()
        propertyWarnerProvider = mockk<ObjectProvider<SimpleDeprecatedConfigWarner>>()
        environment = MockEnvironment()

        // Create scanner with constructor injection
        scanner = DeprecatedPropertyScanner(scanningConfigProvider, propertyWarnerProvider, environment)
    }

    @Test
    fun `scanner should skip processing when scanning config unavailable`() {
        // Given
        every { scanningConfigProvider.getIfAvailable() } returns null
        every { propertyWarnerProvider.getIfAvailable() } returns propertyWarner

        // When
        scanner.afterSingletonsInstantiated()

        // Then
        verify { scanningConfigProvider.getIfAvailable() }
        verify { propertyWarnerProvider.getIfAvailable() }
        verify(exactly = 0) { propertyWarner.warnDeprecatedConditional(any(), any(), any()) }
    }

    @Test
    fun `scanner should process when both components available and enabled`() {
        // Given
        every { scanningConfigProvider.getIfAvailable() } returns scanningConfig
        every { propertyWarnerProvider.getIfAvailable() } returns propertyWarner
        every { scanningConfig.enabled } returns true
        every { scanningConfig.includePackages } returns listOf("com.example.test")
        every { scanningConfig.shouldIncludePackage(any()) } returns true

        // When
        scanner.afterSingletonsInstantiated()

        // Then
        verify { scanningConfigProvider.getIfAvailable() }
        verify { propertyWarnerProvider.getIfAvailable() }
        verify { scanningConfig.enabled }
        verify(atLeast = 1) { scanningConfig.includePackages }
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
        val newRule = DeprecatedPropertyScanner.PropertyMigrationRule(
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
        val rule = DeprecatedPropertyScanner.PropertyMigrationRule(
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
        val conditionalRule = DeprecatedPropertyScanner.PropertyMigrationRule(
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
        val runtimeRule = DeprecatedPropertyScanner.PropertyMigrationRule(
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
        val rule = DeprecatedPropertyScanner.PropertyMigrationRule(
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
        val dotSensitiveRule = DeprecatedPropertyScanner.PropertyMigrationRule(
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
        val rule1 = DeprecatedPropertyScanner.PropertyMigrationRule(
            pattern = Pattern.compile("first\\.(.+)"),
            replacement = "migrated.first.\$1",
            description = "First rule"
        )

        val rule2 = DeprecatedPropertyScanner.PropertyMigrationRule(
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

    @Test
    fun `scanner should skip processing when scanning explicitly disabled`() {
        // Given
        every { scanningConfigProvider.getIfAvailable() } returns scanningConfig
        every { propertyWarnerProvider.getIfAvailable() } returns propertyWarner
        every { scanningConfig.enabled } returns false

        // When
        scanner.afterSingletonsInstantiated()

        // Then
        verify { scanningConfigProvider.getIfAvailable() }
        verify { propertyWarnerProvider.getIfAvailable() }
        verify { scanningConfig.enabled }
        verify(exactly = 0) { propertyWarner.warnDeprecatedConditional(any(), any(), any()) }
    }

    @Test
    fun `scanner should skip processing when both components unavailable`() {
        // Given
        every { scanningConfigProvider.getIfAvailable() } returns null
        every { propertyWarnerProvider.getIfAvailable() } returns null

        // When
        scanner.afterSingletonsInstantiated()

        // Then
        verify { scanningConfigProvider.getIfAvailable() }
        verify { propertyWarnerProvider.getIfAvailable() }
        verify(exactly = 0) { scanningConfig.enabled }
        verify(exactly = 0) { propertyWarner.warnDeprecatedConditional(any(), any(), any()) }
    }
}
