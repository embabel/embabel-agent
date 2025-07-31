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
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.annotation.Conditional
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import org.springframework.core.type.AnnotationMetadata
import org.springframework.core.type.classreading.CachingMetadataReaderFactory
import org.springframework.core.type.classreading.MetadataReader
import org.springframework.stereotype.Component
import java.util.regex.Pattern

/**
 * Scans for deprecated @ConditionalOnProperty annotations during application startup.
 *
 * This component analyzes classes in configured packages to detect usage of deprecated
 * conditional properties and issues warnings through the SimpleDeprecatedConfigWarner.
 * Uses SmartInitializingSingleton to ensure all beans are fully available before scanning.
 *
 * ## Conditional Bean Loading Scenarios
 *
 * This scanner works with conditionally loaded beans based on migration configuration:
 *
 * ### **Scenario 1: Iteration 0 Default (Scanning Disabled)**
 * ```properties
 * # Default behavior - no scanning property set
 * # embabel.agent.platform.migration.scanning.enabled=false (implicit)
 * embabel.agent.platform.migration.warnings.enabled=true (default via matchIfMissing=true)
 * ```
 * **Bean State**: `scanningConfig = null`, `propertyWarner = available`
 * **Behavior**: Scanner logs "scanning disabled", no package scanning occurs
 * **Use Case**: Iteration 0 production - warnings work, scanning deferred to Iteration 1
 *
 * ### **Scenario 2: Warnings Explicitly Disabled**
 * ```properties
 * embabel.agent.platform.migration.warnings.enabled=false
 * embabel.agent.platform.migration.scanning.enabled=false
 * ```
 * **Bean State**: `scanningConfig = null`, `propertyWarner = null`
 * **Behavior**: Scanner logs "migration system completely disabled"
 * **Use Case**: Production environments that want to disable all migration detection
 *
 * ### **Scenario 3: Full Migration Detection (Iteration 1+)**
 * ```properties
 * embabel.agent.platform.migration.warnings.enabled=true
 * embabel.agent.platform.migration.scanning.enabled=true
 * ```
 * **Bean State**: `scanningConfig = available`, `propertyWarner = available`
 * **Behavior**: Full package scanning with conditional annotation detection
 * **Use Case**: Iteration 1+ production - complete migration detection system active
 *
 * ### **Scenario 4: Warnings Only (Partial Detection)**
 * ```properties
 * embabel.agent.platform.migration.warnings.enabled=true
 * # embabel.agent.platform.migration.scanning.enabled=false (default)
 * ```
 * **Bean State**: `scanningConfig = null`, `propertyWarner = available`
 * **Behavior**: Property warnings work, but no @ConditionalOnProperty scanning
 * **Use Case**: Users want property warnings but not bytecode scanning overhead
 *
 * ## Rule Invocation Flow (When Fully Enabled)
 *
 * The migration rules are automatically invoked during Spring application startup
 * whenever deprecated conditional properties are detected in scanned classes:
 *
 * **Example Scenario**: Class `MyService` has `@ConditionalOnProperty("embabel.agent.anthropic.max-attempts")`
 *
 * 1. **Spring startup** → `afterSingletonsInstantiated()` called after all beans initialized
 * 2. **Bean availability check** → Verifies both scanning config and property warner are available
 * 3. **Package scanning** → Finds `MyService.class` in configured include packages
 * 4. **Annotation detection** → Finds `@ConditionalOnProperty` annotation on the class
 * 5. **Property extraction** → Extracts `"embabel.agent.anthropic.max-attempts"` from annotation attributes
 * 6. **Deprecation check** → `isDeprecatedProperty()` returns `true` based on migration rules
 * 7. **Warning generation** → `getRecommendedProperty()` called with deprecated property name
 * 8. **Rule matching** → Rule with pattern `embabel\.agent\.([^.]+)\.max-attempts` matches the property
 * 8. **Transformation** → `$1` captures `"anthropic"`, result = `"embabel.agent.platform.models.anthropic.max-attempts"`
 * 9. **Warning issued** → Logger warns about deprecated usage with recommended replacement property
 *
 * @see PropertyMigrationRule for rule definition structure
 * @see ConditionalPropertyScanningConfig for scanning configuration options
 * @see SimpleDeprecatedConfigWarner for warning output format
 */
@Component
class ConditionalPropertyScanner : SmartInitializingSingleton, ApplicationContextAware {

    private lateinit var applicationContext: ApplicationContext
    private val logger = LoggerFactory.getLogger(ConditionalPropertyScanner::class.java)
    private val resourceResolver = PathMatchingResourcePatternResolver()
    private val metadataReaderFactory = CachingMetadataReaderFactory()

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        this.applicationContext = applicationContext
    }

    /**
     * Performs conditional property scanning after all singletons are initialized.
     *
     * This method handles all conditional bean loading scenarios:
     *
     * <h3>Scenario 1: Iteration 0 Default (Scanning Disabled)</h3>
     * <pre>{@code
     * # Configuration:
     * # embabel.agent.platform.migration.scanning.enabled=false (implicit default)
     * embabel.agent.platform.migration.warnings.enabled=true (default via matchIfMissing=true)
     *
     * # Bean State: scanningConfig = null, propertyWarner = available
     * # Behavior: Logs "scanning disabled", no package scanning occurs
     * # Use Case: Iteration 0 production - warnings work, scanning deferred
     * }</pre>
     *
     * <h3>Scenario 2: Migration System Completely Disabled</h3>
     * <pre>{@code
     * # Configuration:
     * embabel.agent.platform.migration.warnings.enabled=false
     * embabel.agent.platform.migration.scanning.enabled=false
     *
     * # Bean State: scanningConfig = null, propertyWarner = null
     * # Behavior: Logs "migration system disabled", no detection occurs
     * # Use Case: Production environments disabling all migration detection
     * }</pre>
     *
     * <h3>Scenario 3: Full Migration Detection (Iteration 1+)</h3>
     * <pre>{@code
     * # Configuration:
     * embabel.agent.platform.migration.warnings.enabled=true
     * embabel.agent.platform.migration.scanning.enabled=true
     *
     * # Bean State: scanningConfig = available, propertyWarner = available
     * # Behavior: Full package scanning with conditional annotation detection
     * # Use Case: Iteration 1+ production - complete migration system active
     * }</pre>
     *
     * <h3>Scenario 4: Warnings Only (Partial Detection)</h3>
     * <pre>{@code
     * # Configuration:
     * embabel.agent.platform.migration.warnings.enabled=true
     * # embabel.agent.platform.migration.scanning.enabled=false (default)
     *
     * # Bean State: scanningConfig = null, propertyWarner = available
     * # Behavior: Property warnings work, no @ConditionalOnProperty scanning
     * # Use Case: Property warnings without bytecode scanning overhead
     * }</pre>
     *
     * @see ConditionalPropertyScanningConfig for scanning configuration
     * @see SimpleDeprecatedConfigWarner for warning system
     */
    override fun afterSingletonsInstantiated() {
        val scanningConfig = applicationContext.getBeanProvider(ConditionalPropertyScanningConfig::class.java).getIfAvailable()
        val propertyWarner = applicationContext.getBeanProvider(SimpleDeprecatedConfigWarner::class.java).getIfAvailable()

        when {
            scanningConfig == null && propertyWarner == null -> {
                logger.debug("Migration system completely disabled - both scanning config and property warner unavailable")
            }
            scanningConfig == null -> {
                logger.debug("ConditionalPropertyScanningConfig not available - scanning disabled (Scenario 1/4: Iteration 0 default or warnings-only mode)")
            }
            propertyWarner == null -> {
                logger.debug("SimpleDeprecatedConfigWarner not available - cannot issue warnings (Scenario 2: Migration system disabled)")
            }
            !scanningConfig.enabled -> {
                logger.debug("Conditional property scanning explicitly disabled via embabel.agent.platform.migration.scanning.enabled=false")
            }
            else -> {
                logger.info("All migration components available - starting conditional property scanning (Scenario 3: Full detection active)")
                doScanning(scanningConfig, propertyWarner)
            }
        }
    }

    /**
     * Property migration rules with simple runtime extensibility.
     * Uses explicit mappings for predictable, safe migrations.
     */
    private val propertyMigrationRules = mutableListOf<PropertyMigrationRule>()

    /**
     * Explicit property mappings for all known migrations.
     * Simple, predictable, and safe approach without regex complexity.
     */
    private val exactPropertyMappings = mapOf(
        // Platform namespace consolidation (embabel.agent-platform.* → embabel.agent.platform.*)
        "embabel.agent-platform.ranking.max-attempts" to "embabel.agent.platform.ranking.max-attempts",
        "embabel.agent-platform.ranking.backoff-millis" to "embabel.agent.platform.ranking.backoff-millis",
        "embabel.agent-platform.ranking.backoff-multiplier" to "embabel.agent.platform.ranking.backoff-multiplier",
        "embabel.agent-platform.ranking.backoff-max-interval" to "embabel.agent.platform.ranking.backoff-max-interval",
        "embabel.agent-platform.llm-operations.prompts.template" to "embabel.agent.platform.llm-operations.prompts.template",
        "embabel.agent-platform.llm-operations.data-binding.max-attempts" to "embabel.agent.platform.llm-operations.data-binding.max-attempts",
        "embabel.agent-platform.llm-operations.data-binding.fixed-backoff-millis" to "embabel.agent.platform.llm-operations.data-binding.fixed-backoff-millis",
        "embabel.agent-platform.autonomy.agent-confidence-cut-off" to "embabel.agent.platform.autonomy.agent-confidence-cut-off",
        "embabel.agent-platform.autonomy.goal-confidence-cut-off" to "embabel.agent.platform.autonomy.goal-confidence-cut-off",
        "embabel.agent-platform.process-id-generation.include-version" to "embabel.agent.platform.process-id-generation.include-version",
        "embabel.agent-platform.process-id-generation.include-agent-name" to "embabel.agent.platform.process-id-generation.include-agent-name",

        // Model provider configurations (embabel.agent.PROVIDER.* → embabel.agent.platform.models.PROVIDER.*)
        "embabel.agent.anthropic.max-attempts" to "embabel.agent.platform.models.anthropic.max-attempts",
        "embabel.agent.anthropic.backoff-millis" to "embabel.agent.platform.models.anthropic.backoff-millis",
        "embabel.agent.anthropic.backoff-multiplier" to "embabel.agent.platform.models.anthropic.backoff-multiplier",
        "embabel.agent.anthropic.backoff-max-interval" to "embabel.agent.platform.models.anthropic.backoff-max-interval",
        "embabel.agent.openai.max-attempts" to "embabel.agent.platform.models.openai.max-attempts",
        "embabel.agent.openai.backoff-millis" to "embabel.agent.platform.models.openai.backoff-millis",
        "embabel.agent.openai.backoff-multiplier" to "embabel.agent.platform.models.openai.backoff-multiplier",
        "embabel.agent.openai.backoff-max-interval" to "embabel.agent.platform.models.openai.backoff-max-interval",

        // Specific platform feature migrations
        "embabel.agent.enable-scanning" to "embabel.agent.platform.scanning.annotation",
        "embabel.agent.mock-mode" to "embabel.agent.platform.test.mock-mode",
        "embabel.agent.sse.max-buffer-size" to "embabel.agent.platform.sse.max-buffer-size",
        "embabel.agent.sse.max-process-buffers" to "embabel.agent.platform.sse.max-process-buffers",

        // @ConfigurationProperties prefix migrations
        "embabel.anthropic" to "embabel.agent.platform.models.anthropic",
        "embabel.openai" to "embabel.agent.platform.models.openai"
    )

    /**
     * Data class representing a property migration rule with pattern matching.
     *
     * Used for runtime extensibility when explicit mappings are insufficient.
     * Most migrations use explicit mappings in exactPropertyMappings for safety.
     *
     * ## Usage Example (Runtime Extension)
     *
     * ```kotlin
     * PropertyMigrationRule(
     *     pattern = Pattern.compile("custom\\.company\\.(.+)"),
     *     replacement = "embabel.agent.custom.$1",
     *     description = "Custom company namespace migration"
     * )
     * ```
     */
    data class PropertyMigrationRule(
        val pattern: Pattern,
        val replacement: String,
        val description: String,
        val condition: ((String) -> Boolean)? = null
    ) {
        /**
         * Attempts to transform a deprecated property name into its recommended replacement.
         *
         * **IMPORTANT**: This method performs **string transformation only** - it does NOT
         * modify any files or source code. It's used purely for generating migration
         * recommendations that will be shown in warnings.
         *
         * The transformation follows these steps:
         * 1. Check optional condition (if present) - return null if condition fails
         * 2. Apply regex pattern matching against the property name string
         * 3. If pattern matches, perform string substitution using replacement template
         * 4. Return the transformed property name string or null if no match
         *
         * ## Example Usage
         * ```kotlin
         * val rule = PropertyMigrationRule(
         *     pattern = Pattern.compile("embabel\\.agent\\.([^.]+)\\.max-attempts"),
         *     replacement = "embabel.agent.platform.models.$1.max-attempts"
         * )
         *
         * val result = rule.tryApply("embabel.agent.anthropic.max-attempts")
         * // Returns: "embabel.agent.platform.models.anthropic.max-attempts"
         * ```
         *
         * @param property The deprecated property name string to transform
         * @return The recommended replacement property name string if rule applies, null otherwise
         */
        fun tryApply(property: String): String? {
            // Check optional condition first
            if (condition != null && !condition.invoke(property)) {
                return null
            }

            val matcher = pattern.matcher(property)
            return if (matcher.matches()) {
                matcher.replaceAll(replacement)
            } else {
                null
            }
        }
    }

    /**
     * BeanFactoryPostProcessor implementation - called during Spring startup.
     *
     * This method is automatically invoked by Spring during application context initialization,
     * before any beans are instantiated. It triggers the complete scanning and warning process
     * for deprecated conditional properties.
     *
     * **Execution Order**: Runs early in Spring lifecycle, after property sources are loaded
     * but before regular bean creation begins.
     */
    /**
     * Performs the actual scanning with provided configuration and warning components.
     *
     * @param scanningConfig The scanning configuration bean
     * @param propertyWarner The property warning component
     */
    private fun doScanning(scanningConfig: ConditionalPropertyScanningConfig, propertyWarner: SimpleDeprecatedConfigWarner) {
        logger.info("Scanning for deprecated conditional properties in packages: ${scanningConfig.includePackages}")

        try {
            scanForDeprecatedConditionals(scanningConfig, propertyWarner)
        } catch (e: Exception) {
            logger.warn("Error during conditional property scanning: ${e.message}", e)
        }
    }

    /**
     * Scans configured packages for deprecated @ConditionalOnProperty usage.
     *
     * **Step 2 in Rule Invocation Flow**: Package scanning phase that discovers
     * all .class files in the configured include packages and analyzes each one
     * for deprecated conditional annotations.
     */
    private fun scanForDeprecatedConditionals(scanningConfig: ConditionalPropertyScanningConfig, propertyWarner: SimpleDeprecatedConfigWarner) {
        var scannedClasses = 0
        var foundDeprecated = 0

        scanningConfig.includePackages.forEach { packageName ->
            if (scanningConfig.shouldIncludePackage(packageName)) {
                val packageResources = findClassesInPackage(packageName, scanningConfig)
                packageResources.forEach { resource ->
                    try {
                        val metadataReader = metadataReaderFactory.getMetadataReader(resource)
                        scannedClasses++

                        if (analyzeClassForDeprecatedConditionals(metadataReader, scanningConfig, propertyWarner)) {
                            foundDeprecated++
                        }
                    } catch (e: Exception) {
                        logger.debug("Error reading class metadata for ${resource}: ${e.message}")
                    }
                }
            }
        }

        logger.info("Conditional property scanning completed. Scanned: $scannedClasses classes, Found deprecated: $foundDeprecated")
    }

    /**
     * Finds all class resources in a given package.
     *
     * Uses Spring's PathMatchingResourcePatternResolver to locate .class files
     * and optionally filters out JAR-based classes based on configuration.
     */
    private fun findClassesInPackage(packageName: String, scanningConfig: ConditionalPropertyScanningConfig): Array<org.springframework.core.io.Resource> {
        val packagePath = packageName.replace('.', '/')
        val pattern = "classpath*:$packagePath/**/*.class"

        return try {
            val resources = resourceResolver.getResources(pattern)

            // Filter out JAR-based packages if auto-exclude is enabled
            if (scanningConfig.autoExcludeJarPackages) {
                resources.filter { resource ->
                    val url = resource.url.toString()
                    !url.contains(".jar!") && !url.contains(".war!")
                }.toTypedArray()
            } else {
                resources
            }
        } catch (e: Exception) {
            logger.debug("Error finding classes in package $packageName: ${e.message}")
            emptyArray()
        }
    }

    /**
     * Analyzes a class for deprecated conditional annotations.
     *
     * **Step 3 in Rule Invocation Flow**: Annotation detection phase that examines
     * class metadata to find @ConditionalOnProperty and related annotations.
     *
     * @param metadataReader Spring metadata reader for the class
     * @return true if deprecated conditionals were found in this class
     */
    private fun analyzeClassForDeprecatedConditionals(metadataReader: MetadataReader, scanningConfig: ConditionalPropertyScanningConfig, propertyWarner: SimpleDeprecatedConfigWarner): Boolean {
        val className = metadataReader.classMetadata.className
        val annotationMetadata = metadataReader.annotationMetadata

        // Skip if package should be excluded
        if (scanningConfig.shouldExcludePackage(className)) {
            return false
        }

        var foundDeprecated = false

        // Check for @ConditionalOnProperty annotations
        if (annotationMetadata.hasAnnotation(ConditionalOnProperty::class.java.name)) {
            foundDeprecated = analyzeConditionalOnProperty(className, annotationMetadata, propertyWarner) || foundDeprecated
        }

        // Check for meta-annotations that might contain @ConditionalOnProperty
        annotationMetadata.annotationTypes.forEach { annotationType ->
            if (annotationType != ConditionalOnProperty::class.java.name) {
                try {
                    val metaClass = Class.forName(annotationType)
                    if (metaClass.isAnnotationPresent(ConditionalOnProperty::class.java) ||
                        metaClass.isAnnotationPresent(Conditional::class.java)) {

                        logger.debug("Found meta-annotation with conditional logic in $className: $annotationType")
                        // Could analyze meta-annotations further if needed
                    }
                } catch (e: ClassNotFoundException) {
                    // Ignore - annotation class not available
                }
            }
        }

        return foundDeprecated
    }

    /**
     * Analyzes @ConditionalOnProperty annotation for deprecated properties.
     *
     * **Step 4 in Rule Invocation Flow**: Property extraction phase that reads
     * annotation attributes to extract property names and check for deprecation.
     *
     * @param className The name of the class being analyzed
     * @param annotationMetadata Metadata containing annotation information
     * @return true if deprecated properties were found in the annotation
     */
    private fun analyzeConditionalOnProperty(className: String, annotationMetadata: AnnotationMetadata, propertyWarner: SimpleDeprecatedConfigWarner): Boolean {
        val attributes = annotationMetadata.getAnnotationAttributes(ConditionalOnProperty::class.java.name)
            ?: return false

        val name = attributes["name"] as? String
        val prefix = attributes["prefix"] as? String
        val value = attributes["value"] as? Array<*>

        var foundDeprecated = false

        // Check single property name
        name?.let { propertyName ->
            val fullPropertyName = if (prefix != null) "$prefix.$propertyName" else propertyName
            if (isDeprecatedProperty(fullPropertyName)) {
                issueDeprecatedConditionalWarning(className, fullPropertyName, propertyWarner)
                foundDeprecated = true
            }
        }

        // Check property array
        value?.forEach { prop ->
            val propertyName = prop.toString()
            val fullPropertyName = if (prefix != null) "$prefix.$propertyName" else propertyName
            if (isDeprecatedProperty(fullPropertyName)) {
                issueDeprecatedConditionalWarning(className, fullPropertyName, propertyWarner)
                foundDeprecated = true
            }
        }

        return foundDeprecated
    }

    /**
     * Checks if a property is known to be deprecated using explicit mappings.
     *
     * **Step 5 in Rule Invocation Flow**: Deprecation check that determines whether
     * a property requires migration based on explicit property mappings.
     *
     * @param propertyName The full property name to check
     * @return true if the property is deprecated and should trigger a warning
     */
    private fun isDeprecatedProperty(propertyName: String): Boolean {
        // Check exact mappings first
        if (exactPropertyMappings.containsKey(propertyName)) {
            return true
        }

        // Check runtime rules
        return propertyMigrationRules.any { rule ->
            rule.tryApply(propertyName) != null
        }
    }

    /**
     * Issues a warning for deprecated conditional property usage.
     *
     * **Step 6 in Rule Invocation Flow**: Warning generation phase that creates
     * and logs deprecation warnings with recommended replacement properties.
     *
     * @param className The class containing the deprecated annotation
     * @param propertyName The deprecated property name
     */
    private fun issueDeprecatedConditionalWarning(className: String, propertyName: String, propertyWarner: SimpleDeprecatedConfigWarner) {
        val recommendedProperty = getRecommendedProperty(propertyName)

        val annotationDetails = "@ConditionalOnProperty(\"$propertyName\")"
        val recommendedApproach = "@ConditionalOnProperty(\"$recommendedProperty\")"

        propertyWarner.warnDeprecatedConditional(
            className = className,
            annotationDetails = annotationDetails,
            recommendedApproach = recommendedApproach
        )
    }

    /**
     * Generates recommended property name using explicit mappings.
     *
     * **Steps 7-8 in Rule Invocation Flow**: Simple lookup that converts
     * deprecated properties into recommended replacements using explicit mappings.
     *
     * @param deprecatedProperty The deprecated property name
     * @return The recommended replacement property name
     */
    private fun getRecommendedProperty(deprecatedProperty: String): String {
        // Check exact mappings first
        exactPropertyMappings[deprecatedProperty]?.let { return it }

        // Check runtime rules
        propertyMigrationRules.forEach { rule ->
            rule.tryApply(deprecatedProperty)?.let { return it }
        }

        // Fallback
        return "$deprecatedProperty (please check migration guide for specific replacement)"
    }

    /**
     * Adds a new property migration rule at runtime.
     * Useful for extending migration support without code changes.
     */
    fun addMigrationRule(rule: PropertyMigrationRule) {
        propertyMigrationRules.add(rule)
        logger.debug("Added migration rule: ${rule.description}")
    }

    /**
     * Gets all currently configured migration rules.
     */
    fun getMigrationRules(): List<PropertyMigrationRule> = propertyMigrationRules.toList()
}
