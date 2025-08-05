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

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * Simple implementation for warning about deprecated configuration usage.
 *
 * Provides centralized logging and tracking of deprecated configuration warnings
 * during the migration period, including properties, profiles, and conditional annotations.
 * Warnings are rate-limited to prevent log spam and can be aggregated for overview.
 */
@Component
@ConditionalOnProperty(
    name = ["embabel.agent.platform.migration.warnings.enabled"],
    havingValue = "true",
    matchIfMissing = true
)
class SimpleDeprecatedConfigWarner(
    private val environment: Environment,
    /**
     * Configuration for individual warning logging behavior.
     * When true, individual warnings are logged immediately.
     * When false, only aggregated summary is logged via logAggregatedSummary().
     */
    private val enableIndividualLogging: Boolean = false
) {

    private val warnedProperties = mutableSetOf<String>()
    private val deprecationDetails = mutableMapOf<String, DeprecationInfo>()

    /**
     * Issue a warning for deprecated property usage.
     * Each unique property is warned about only once per application lifecycle.
     *
     * @param deprecatedProperty The deprecated property name
     * @param recommendedProperty The recommended replacement property
     * @param deprecationReason Optional reason for deprecation
     */
    fun warnDeprecatedProperty(
        deprecatedProperty: String,
        recommendedProperty: String,
        deprecationReason: String? = null
    ) {
        // Rate limiting: only warn once per property per application run
        if (deprecatedProperty in warnedProperties) return

        environment.getProperty(deprecatedProperty)?.let { propertyValue ->
            warnedProperties.add(deprecatedProperty)
            deprecationDetails[deprecatedProperty] = DeprecationInfo(
                type = DeprecationType.PROPERTY,
                deprecatedItem = deprecatedProperty,
                recommendedReplacement = recommendedProperty,
                reason = deprecationReason
            )

            val message = buildDeprecatedPropertyMessage(
                deprecatedProperty,
                recommendedProperty,
                deprecationReason,
                propertyValue
            )

            if (enableIndividualLogging) {
                logger.warn(message)
            }
        }
    }

    /**
     * Issue a warning for deprecated profile usage.
     * Each unique profile is warned about only once per application lifecycle.
     *
     * @param deprecatedProfile The deprecated profile name
     * @param recommendedProperty The recommended replacement property
     * @param deprecationReason Optional reason for deprecation
     */
    fun warnDeprecatedProfile(
        deprecatedProfile: String,
        recommendedProperty: String,
        deprecationReason: String? = null
    ) {
        val warningKey = "PROFILE:$deprecatedProfile"

        // Rate limiting: only warn once per profile per application run
        if (warningKey in warnedProperties) return

        if (deprecatedProfile in environment.activeProfiles) {
            warnedProperties.add(warningKey)
            deprecationDetails[warningKey] = DeprecationInfo(
                type = DeprecationType.PROFILE,
                deprecatedItem = deprecatedProfile,
                recommendedReplacement = "$recommendedProperty=true",
                reason = deprecationReason
            )

            val message = buildDeprecatedProfileMessage(deprecatedProfile, recommendedProperty, deprecationReason)

            if (enableIndividualLogging) {
                logger.warn(message)
            }
        }
    }

    /**
     * Issue a warning for deprecated conditional annotation usage.
     *
     * @param className The class containing the deprecated annotation
     * @param annotationDetails Details about the deprecated annotation
     * @param recommendedApproach The recommended replacement approach
     */
    fun warnDeprecatedConditional(
        className: String,
        annotationDetails: String,
        recommendedApproach: String
    ) {
        val warningKey = "CONDITIONAL:$className"

        // Rate limiting: only warn once per class per application run
        if (warningKey in warnedProperties) return

        warnedProperties.add(warningKey)
        deprecationDetails[warningKey] = DeprecationInfo(
            type = DeprecationType.CONDITIONAL,
            deprecatedItem = className,
            recommendedReplacement = recommendedApproach,
            reason = "Conditional annotation migration"
        )

        val message = buildDeprecatedConditionalMessage(className, annotationDetails, recommendedApproach)

        if (enableIndividualLogging) {
            logger.warn(message)
        }
    }

    /**
     * Log an aggregated summary of all deprecated configuration usage.
     * Provides a high-level overview with migration details for better user experience.
     */
    fun logAggregatedSummary() {
        if (deprecationDetails.isEmpty()) return

        val categories = getDeprecationCategories()
        val message = buildAggregatedSummaryMessage(categories)
        logger.warn(message)
    }

    /**
     * Get categorized deprecation information for analysis or reporting.
     */
    fun getDeprecationCategories(): DeprecationCategories {
        val (properties, profiles, conditionals) = deprecationDetails.values
            .groupBy { it.type }
            .let { grouped ->
                Triple(
                    grouped[DeprecationType.PROPERTY].orEmpty(),
                    grouped[DeprecationType.PROFILE].orEmpty(),
                    grouped[DeprecationType.CONDITIONAL].orEmpty()
                )
            }

        return DeprecationCategories(properties, profiles, conditionals)
    }

    /**
     * Get the count of unique deprecated warnings issued.
     */
    fun getWarningCount(): Int = warnedProperties.size

    /**
     * Get the list of deprecated properties/profiles that have been warned about.
     */
    fun getWarnedItems(): Set<String> = warnedProperties.toSet()

    /**
     * Clear all warning tracking (mainly for testing purposes).
     */
    fun clearWarnings() {
        warnedProperties.clear()
        deprecationDetails.clear()
    }

    private fun buildDeprecatedPropertyMessage(
        deprecatedProperty: String,
        recommendedProperty: String,
        deprecationReason: String?,
        propertyValue: String
    ): String = buildString {
        append("DEPRECATED PROPERTY USAGE: Property '$deprecatedProperty' is deprecated and will be removed in a future version.")
        append(" Please migrate to '$recommendedProperty' instead.")
        deprecationReason?.let { append(" Reason: $it") }
        append(" Current value: '$propertyValue'")
    }

    private fun buildDeprecatedProfileMessage(
        deprecatedProfile: String,
        recommendedProperty: String,
        deprecationReason: String?
    ): String = buildString {
        append("DEPRECATED PROFILE USAGE: Profile '$deprecatedProfile' is deprecated and will be removed in a future version.")
        append(" Please migrate to property-based configuration using '$recommendedProperty=true' instead.")
        deprecationReason?.let { append(" Reason: $it") }
    }

    private fun buildDeprecatedConditionalMessage(
        className: String,
        annotationDetails: String,
        recommendedApproach: String
    ): String = buildString {
        append("DEPRECATED CONDITIONAL USAGE: Class '$className' uses deprecated conditional annotation: $annotationDetails.")
        append(" Please migrate to: $recommendedApproach")
    }

    private fun buildAggregatedSummaryMessage(categories: DeprecationCategories): String = buildString {
        append("DEPRECATED CONFIGURATION SUMMARY: Found ")

        val parts = buildList {
            if (categories.properties.isNotEmpty()) add("${categories.properties.size} deprecated properties")
            if (categories.profiles.isNotEmpty()) add("${categories.profiles.size} deprecated profiles")
            if (categories.conditionals.isNotEmpty()) add("${categories.conditionals.size} deprecated conditionals")
        }

        append(parts.joinToString(", "))
        append(". See migration guide for details.")

        // Add migration details
        if (categories.properties.isNotEmpty()) {
            append("\n  Properties: ")
            append(categories.properties.joinToString(", ") { "${it.deprecatedItem} → ${it.recommendedReplacement}" })
        }

        if (categories.profiles.isNotEmpty()) {
            append("\n  Profiles: ")
            append(categories.profiles.joinToString(", ") { "${it.deprecatedItem} → ${it.recommendedReplacement}" })
        }

        if (categories.conditionals.isNotEmpty()) {
            append("\n  Conditionals: ")
            append(categories.conditionals.joinToString(", ") { "${it.deprecatedItem}: @ConditionalOnProperty migration needed" })
        }
    }

    /**
     * Data class for storing deprecation information.
     */
    data class DeprecationInfo(
        val type: DeprecationType,
        val deprecatedItem: String,
        val recommendedReplacement: String,
        val reason: String? = null
    )

    /**
     * Categories of deprecation types.
     */
    enum class DeprecationType {
        PROPERTY, PROFILE, CONDITIONAL
    }

    /**
     * Organized deprecation information by category.
     */
    data class DeprecationCategories(
        val properties: List<DeprecationInfo>,
        val profiles: List<DeprecationInfo>,
        val conditionals: List<DeprecationInfo>
    )

    companion object {
        private val logger = LoggerFactory.getLogger(SimpleDeprecatedConfigWarner::class.java)
    }
}
