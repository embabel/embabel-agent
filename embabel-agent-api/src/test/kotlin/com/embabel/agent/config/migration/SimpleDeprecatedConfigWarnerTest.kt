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
import org.springframework.mock.env.MockEnvironment

/**
 * Tests for SimpleDeprecatedConfigWarner warning functionality.
 */
class SimpleDeprecatedConfigWarnerTest {

    private lateinit var environment: MockEnvironment
    private lateinit var warner: SimpleDeprecatedConfigWarner
    private lateinit var listAppender: ListAppender<ILoggingEvent>
    private lateinit var logger: Logger

    @BeforeEach
    fun setUp() {
        environment = MockEnvironment()
        warner = SimpleDeprecatedConfigWarner(environment)

        // Set up log capture
        logger = LoggerFactory.getLogger(SimpleDeprecatedConfigWarner::class.java) as Logger
        listAppender = ListAppender()
        listAppender.start()
        logger.addAppender(listAppender)
        logger.level = Level.WARN
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(listAppender)
        listAppender.stop()
    }

    @Test
    fun `warnDeprecatedProperty should issue warning when property exists`() {
        // Given
        environment.setProperty("old.property", "test-value")
        warner.enableIndividualLogging = true

        // When
        warner.warnDeprecatedProperty(
            deprecatedProperty = "old.property",
            recommendedProperty = "new.property",
            deprecationReason = "Property consolidation"
        )

        // Then
        assertThat(listAppender.list).hasSize(1)
        val logEvent = listAppender.list[0]
        assertThat(logEvent.level).isEqualTo(Level.WARN)
        assertThat(logEvent.message).contains(
            "DEPRECATED PROPERTY USAGE",
            "old.property",
            "new.property",
            "Property consolidation",
            "test-value"
        )

        assertThat(warner.getWarningCount()).isEqualTo(1)
        assertThat(warner.getWarnedItems()).contains("old.property")
    }

    @Test
    fun `warnDeprecatedProperty should not warn when property does not exist`() {
        // When
        warner.warnDeprecatedProperty(
            deprecatedProperty = "nonexistent.property",
            recommendedProperty = "new.property"
        )

        // Then
        assertThat(listAppender.list).isEmpty()
        assertThat(warner.getWarningCount()).isEqualTo(0)
    }

    @Test
    fun `warnDeprecatedProperty should warn only once per property`() {
        // Given
        environment.setProperty("duplicate.property", "value")
        warner.enableIndividualLogging = true

        // When - warn multiple times
        warner.warnDeprecatedProperty("duplicate.property", "new.property")
        warner.warnDeprecatedProperty("duplicate.property", "new.property")
        warner.warnDeprecatedProperty("duplicate.property", "different.property")

        // Then - only one warning logged
        assertThat(listAppender.list).hasSize(1)
        assertThat(warner.getWarningCount()).isEqualTo(1)
    }

    @Test
    fun `warnDeprecatedProfile should issue warning when profile is active`() {
        // Given
        environment.setActiveProfiles("deprecated-profile")
        warner.enableIndividualLogging = true

        // When
        warner.warnDeprecatedProfile(
            deprecatedProfile = "deprecated-profile",
            recommendedProperty = "embabel.agent.feature.enabled",
            deprecationReason = "Profile-to-property migration"
        )

        // Then
        assertThat(listAppender.list).hasSize(1)
        val logEvent = listAppender.list[0]
        assertThat(logEvent.level).isEqualTo(Level.WARN)
        assertThat(logEvent.message).contains(
            "DEPRECATED PROFILE USAGE",
            "deprecated-profile",
            "embabel.agent.feature.enabled=true",
            "Profile-to-property migration"
        )

        assertThat(warner.getWarningCount()).isEqualTo(1)
        assertThat(warner.getWarnedItems()).contains("PROFILE:deprecated-profile")
    }

    @Test
    fun `warnDeprecatedProfile should not warn when profile is not active`() {
        // Given - no active profiles set or different profile active
        environment.setActiveProfiles("other-profile")

        // When
        warner.warnDeprecatedProfile(
            deprecatedProfile = "deprecated-profile",
            recommendedProperty = "embabel.agent.feature.enabled"
        )

        // Then
        assertThat(listAppender.list).isEmpty()
        assertThat(warner.getWarningCount()).isEqualTo(0)
    }

    @Test
    fun `warnDeprecatedConditional should issue warning with class and annotation details`() {
        // Given
        warner.enableIndividualLogging = true

        // When
        warner.warnDeprecatedConditional(
            className = "com.example.MyService",
            annotationDetails = "@ConditionalOnProperty(\"old.property\")",
            recommendedApproach = "@ConditionalOnProperty(\"new.property\")"
        )

        // Then
        assertThat(listAppender.list).hasSize(1)
        val logEvent = listAppender.list[0]
        assertThat(logEvent.level).isEqualTo(Level.WARN)
        assertThat(logEvent.message).contains(
            "DEPRECATED CONDITIONAL USAGE",
            "com.example.MyService",
            "@ConditionalOnProperty(\"old.property\")",
            "@ConditionalOnProperty(\"new.property\")"
        )

        assertThat(warner.getWarningCount()).isEqualTo(1)
        assertThat(warner.getWarnedItems()).contains("CONDITIONAL:com.example.MyService")
    }

    @Test
    fun `warnDeprecatedConditional should warn only once per class`() {
        // Given
        warner.enableIndividualLogging = true

        // When - warn multiple times for same class
        warner.warnDeprecatedConditional(
            "com.example.Service",
            "@ConditionalOnProperty(\"prop1\")",
            "@ConditionalOnProperty(\"new.prop1\")"
        )
        warner.warnDeprecatedConditional(
            "com.example.Service",
            "@ConditionalOnProperty(\"prop2\")",
            "@ConditionalOnProperty(\"new.prop2\")"
        )

        // Then - only one warning per class
        assertThat(listAppender.list).hasSize(1)
        assertThat(warner.getWarningCount()).isEqualTo(1)
    }

    @Test
    fun `should handle multiple different warning types`() {
        // Given
        environment.setProperty("old.prop", "value")
        environment.setActiveProfiles("old-profile")
        warner.enableIndividualLogging = true

        // When - different types of warnings
        warner.warnDeprecatedProperty("old.prop", "new.prop")
        warner.warnDeprecatedProfile("old-profile", "new.property")
        warner.warnDeprecatedConditional("MyClass", "@ConditionalOnProperty(\"test\")", "new approach")

        // Then - all warnings issued
        assertThat(listAppender.list).hasSize(3)
        assertThat(warner.getWarningCount()).isEqualTo(3)
        assertThat(warner.getWarnedItems()).containsExactlyInAnyOrder(
            "old.prop",
            "PROFILE:old-profile",
            "CONDITIONAL:MyClass"
        )
    }

    @Test
    fun `logAggregatedSummary should provide overview of all deprecated configuration usage`() {
        // Given - various deprecated configurations
        environment.setProperty("old.prop1", "value1")
        environment.setProperty("old.prop2", "value2")
        environment.setActiveProfiles("old-profile1", "old-profile2")

        warner.warnDeprecatedProperty("old.prop1", "new.prop1")
        warner.warnDeprecatedProperty("old.prop2", "new.prop2")
        warner.warnDeprecatedProfile("old-profile1", "new.feature1.enabled")
        warner.warnDeprecatedProfile("old-profile2", "new.feature2.enabled")
        warner.warnDeprecatedConditional("MyService", "@ConditionalOnProperty(\"test\")", "new approach")

        // Clear existing individual warnings for cleaner test
        listAppender.list.clear()

        // When
        warner.logAggregatedSummary()

        // Then - single aggregated log message
        assertThat(listAppender.list).hasSize(1)
        val summaryMessage = listAppender.list[0].message

        assertThat(summaryMessage).contains(
            "DEPRECATED CONFIGURATION SUMMARY",
            "2 deprecated properties",
            "2 deprecated profiles",
            "1 deprecated conditionals"
        )

        // Should contain migration details
        assertThat(summaryMessage).contains(
            "old.prop1 → new.prop1",
            "old.prop2 → new.prop2",
            "old-profile1 → new.feature1.enabled=true",
            "old-profile2 → new.feature2.enabled=true",
            "MyService: @ConditionalOnProperty"
        )
    }

    @Test
    fun `logAggregatedSummary should handle empty state gracefully`() {
        // When - no deprecated configurations warned about
        warner.logAggregatedSummary()

        // Then - no log message (or informational message about clean state)
        assertThat(listAppender.list).isEmpty()
    }

    @Test
    fun `clearWarnings should reset warning tracking`() {
        // Given - some warnings issued
        environment.setProperty("test.prop", "value")
        warner.enableIndividualLogging = true
        warner.warnDeprecatedProperty("test.prop", "new.prop")
        assertThat(warner.getWarningCount()).isEqualTo(1)

        // When
        warner.clearWarnings()

        // Then
        assertThat(warner.getWarningCount()).isEqualTo(0)
        assertThat(warner.getWarnedItems()).isEmpty()

        // And should allow warning again for same property
        warner.warnDeprecatedProperty("test.prop", "new.prop")
        assertThat(listAppender.list).hasSize(2) // Original + new warning
    }

    @Test
    fun `should handle warnings without optional deprecation reason`() {
        // Given
        environment.setProperty("simple.prop", "value")
        warner.enableIndividualLogging = true

        // When - no deprecation reason provided
        warner.warnDeprecatedProperty("simple.prop", "new.prop")

        // Then
        assertThat(listAppender.list).hasSize(1)
        val logEvent = listAppender.list[0]
        assertThat(logEvent.message).contains("simple.prop", "new.prop")
        assertThat(logEvent.message).doesNotContain("Reason:")
    }

    @Test
    fun `should handle edge cases with empty or null values`() {
        // Given
        warner.enableIndividualLogging = true

        // Test empty property values
        environment.setProperty("empty.prop", "")
        warner.warnDeprecatedProperty("empty.prop", "new.prop")
        assertThat(listAppender.list).hasSize(1)
        assertThat(listAppender.list[0].message).contains("Current value: ''")

        // Test with empty profiles array
        environment.setActiveProfiles() // No active profiles
        warner.warnDeprecatedProfile("test-profile", "new.prop")
        assertThat(listAppender.list).hasSize(1) // Only the property warning from above
    }

    @Test
    fun `getDeprecationCategories should return organized deprecation info`() {
        // Given
        environment.setProperty("old.prop", "value")
        environment.setActiveProfiles("old-profile")

        warner.warnDeprecatedProperty("old.prop", "new.prop")
        warner.warnDeprecatedProfile("old-profile", "new.feature.enabled")
        warner.warnDeprecatedConditional("MyClass", "@ConditionalOnProperty(\"test\")", "new approach")

        // When
        val categories = warner.getDeprecationCategories()

        // Then
        assertThat(categories.properties).hasSize(1)
        assertThat(categories.profiles).hasSize(1)
        assertThat(categories.conditionals).hasSize(1)

        assertThat(categories.properties[0].deprecatedItem).isEqualTo("old.prop")
        assertThat(categories.properties[0].recommendedReplacement).isEqualTo("new.prop")

        assertThat(categories.profiles[0].deprecatedItem).isEqualTo("old-profile")
        assertThat(categories.profiles[0].recommendedReplacement).isEqualTo("new.feature.enabled=true")

        assertThat(categories.conditionals[0].deprecatedItem).isEqualTo("MyClass")
        assertThat(categories.conditionals[0].recommendedReplacement).contains("new approach")
    }

    @Test
    fun `should support disabling individual logging while keeping aggregated logging`() {
        // Given
        environment.setProperty("test.prop", "value")
        warner.enableIndividualLogging = false

        // When - individual warning called
        warner.warnDeprecatedProperty("test.prop", "new.prop")

        // Then - no individual log message
        assertThat(listAppender.list).isEmpty()
        assertThat(warner.getWarningCount()).isEqualTo(1) // Still tracked for aggregation

        // When - aggregated summary called
        warner.logAggregatedSummary()

        // Then - aggregated log message appears
        assertThat(listAppender.list).hasSize(1)
        assertThat(listAppender.list[0].message).contains("DEPRECATED CONFIGURATION SUMMARY")
    }

    @Test
    fun `should not log individually when enableIndividualLogging is false (default behavior)`() {
        // Given
        environment.setProperty("test.prop", "value")
        assertThat(warner.enableIndividualLogging).isFalse() // Default value

        // When
        warner.warnDeprecatedProperty("test.prop", "new.prop")

        // Then - no individual warning logged
        assertThat(listAppender.list).isEmpty()
        assertThat(warner.getWarningCount()).isEqualTo(1) // Still tracked for aggregation
    }

    @Test
    fun `should handle mixed individual and aggregated logging configurations`() {
        // Given
        environment.setProperty("prop1", "value1")
        environment.setProperty("prop2", "value2")

        // When - log first property with individual logging enabled
        warner.enableIndividualLogging = true
        warner.warnDeprecatedProperty("prop1", "new.prop1")
        assertThat(listAppender.list).hasSize(1)

        // When - disable individual logging and log second property
        warner.enableIndividualLogging = false
        warner.warnDeprecatedProperty("prop2", "new.prop2")
        assertThat(listAppender.list).hasSize(1) // No new individual log

        // When - log aggregated summary
        warner.logAggregatedSummary()

        // Then - aggregated summary includes both properties
        assertThat(listAppender.list).hasSize(2)
        val summaryMessage = listAppender.list[1].message
        assertThat(summaryMessage).contains("2 deprecated properties")
        assertThat(summaryMessage).contains("prop1 → new.prop1")
        assertThat(summaryMessage).contains("prop2 → new.prop2")
    }
}
