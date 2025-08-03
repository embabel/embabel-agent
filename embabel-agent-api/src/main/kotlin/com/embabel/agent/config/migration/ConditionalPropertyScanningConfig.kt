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

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * Configuration for conditional property scanning during migration.
 *
 * Controls which packages are scanned for deprecated property in @ConditionalOnProperty annotations.
 * Uses a flexible approach with configurable include/exclude patterns to handle
 * diverse project structures and dependencies.
 */
@Configuration
@ConfigurationProperties("embabel.agent.platform.migration.scanning")
@ConditionalOnProperty(
    name = ["embabel.agent.platform.migration.scanning.enabled"],
    havingValue = "true",
    matchIfMissing = false
)
data class ConditionalPropertyScanningConfig(
    /**
     * Base packages to scan for deprecated conditional annotations.
     * Defaults to actual Embabel packages while excluding framework internals.
     *
     * Current packages:
     * - com.embabel.agent: Main agent framework code
     * - com.embabel.agent.shell: Shell module (in embabel-agent-shell artifact)
     *
     * Future packages (when they exist):
     * - com.embabel.plugin: Plugin system
     * - com.embabel.extension: Extension system
     *
     * ## Configuration Override Examples
     *
     * ### application.properties
     * ```properties
     * # Override to scan custom packages
     * embabel.agent.platform.migration.scanning.include-packages[0]=com.embabel.agent
     * embabel.agent.platform.migration.scanning.include-packages[1]=com.mycorp.custom
     * embabel.agent.platform.migration.scanning.include-packages[2]=com.thirdparty.integration
     *
     * # Add additional excludes
     * embabel.agent.platform.migration.scanning.additional-excludes[0]=com.noisy.framework
     * embabel.agent.platform.migration.scanning.additional-excludes[1]=com.slow.scanner
     *
     * # Disable auto JAR exclusion for comprehensive scanning
     * embabel.agent.platform.migration.scanning.auto-exclude-jar-packages=false
     * ```
     *
     * ### application.yml
     * ```yaml
     * embabel:
     *   agent:
     *     platform:
     *       migration:
     *         scanning:
     *           include-packages:
     *             - com.embabel.agent
     *             - com.mycorp.custom
     *             - com.thirdparty.integration
     *           additional-excludes:
     *             - com.noisy.framework
     *             - com.slow.scanner
     *           auto-exclude-jar-packages: false
     * ```
     *
     * ### Environment Variables
     * ```bash
     * # Include packages (comma-separated)
     * export EMBABEL_AGENT_PLATFORM_MIGRATION_SCANNING_INCLUDE_PACKAGES=com.embabel.agent,com.mycorp.custom
     *
     * # Additional excludes (comma-separated)
     * export EMBABEL_AGENT_PLATFORM_MIGRATION_SCANNING_ADDITIONAL_EXCLUDES=com.noisy.framework,com.slow.scanner
     *
     * # Disable auto JAR exclusion
     * export EMBABEL_AGENT_PLATFORM_MIGRATION_SCANNING_AUTO_EXCLUDE_JAR_PACKAGES=false
     *
     * # Disable scanning entirely in production
     * export EMBABEL_AGENT_PLATFORM_MIGRATION_SCANNING_ENABLED=false
     * ```
     *
     * @see PROFILES_MIGRATION_GUIDE.md for additional configuration examples and migration guidance
     */
    val includePackages: List<String> = listOf(
        "com.embabel.agent",
        "com.embabel.agent.shell"
    ),

    /**
     * Package prefixes to exclude from scanning.
     * Uses a comprehensive strategy that excludes common framework and library packages
     * while allowing configuration override for custom environments.
     */
    val excludePackages: List<String> = defaultExcludePackages(),

    /**
     * Additional user-specific packages to exclude.
     * Allows runtime customization without modifying the default exclusion list.
     */
    val additionalExcludes: List<String> = emptyList(),

    /**
     * Whether to use classpath-based detection to automatically exclude JAR-based packages.
     * When enabled, packages from JAR files are automatically excluded from scanning.
     */
    val autoExcludeJarPackages: Boolean = true,

    /**
     * Maximum depth for package scanning to prevent excessive recursion.
     */
    val maxScanDepth: Int = 10,

    /**
     * Whether scanning is enabled.
     * Disabled by default in Iteration 0, should be enabled starting Iteration 1
     * when users need migration detection for @ConditionalOnProperty annotations.
     */
    val enabled: Boolean = false
) {

    companion object {
        /**
         * Comprehensive default exclude list covering common frameworks and libraries.
         * This approach uses a "kitchen sink" strategy to ensure robust filtering.
         */
        fun defaultExcludePackages(): List<String> = listOf(
            // JDK packages
            "java.",
            "javax.",
            "jdk.",
            "sun.",
            "com.sun.",

            // Kotlin runtime
            "kotlin.",
            "kotlinx.",

            // Spring Framework ecosystem
            "org.springframework.",
            "org.springframework.boot.",
            "org.springframework.security.",
            "org.springframework.data.",
            "org.springframework.cloud.",
            "org.springframework.integration.",
            "org.springframework.batch.",
            "org.springframework.test.",

            // Apache Commons and utilities
            "org.apache.",
            "org.eclipse.",
            "org.junit.",
            "org.hamcrest.",
            "org.mockito.",
            "org.testng.",
            "org.assertj.",

            // Jackson and serialization
            "com.fasterxml.jackson.",
            "com.google.gson.",
            "org.json.",

            // Logging frameworks
            "org.slf4j.",
            "ch.qos.logback.",
            "org.apache.logging.",
            "org.apache.log4j.",

            // Metrics and monitoring
            "io.micrometer.",
            "io.prometheus.",
            "com.codahale.metrics.",
            "org.influxdb.",
            "com.newrelic.",
            "com.datadog.",

            // Database and persistence
            "org.hibernate.",
            "org.mybatis.",
            "com.zaxxer.hikari.",
            "org.h2.",
            "org.postgresql.",
            "com.mysql.",
            "oracle.jdbc.",
            "com.microsoft.sqlserver.",
            "redis.clients.",
            "org.mongodb.",
            "org.neo4j.",

            // Web and HTTP
            "org.apache.tomcat.",
            "org.apache.catalina.",
            "io.undertow.",
            "org.eclipse.jetty.",
            "io.netty.",
            "com.squareup.okhttp.",
            "org.apache.http.",

            // Security
            "org.bouncycastle.",
            "org.jasypt.",
            "io.jsonwebtoken.",

            // Build and development tools
            "org.gradle.",
            "org.apache.maven.",
            "com.github.",
            "org.jetbrains.",

            // Cloud and infrastructure
            "com.amazonaws.",
            "com.azure.",
            "com.google.cloud.",
            "io.kubernetes.",
            "io.fabric8.",

            // Reactive frameworks
            "io.reactivex.",
            "reactor.",
            "org.reactivestreams.",

            // Serialization and validation
            "javax.validation.",
            "jakarta.validation.",
            "org.apache.avro.",
            "com.thoughtworks.xstream.",

            // Template engines
            "org.thymeleaf.",
            "org.apache.velocity.",
            "freemarker.",

            // Configuration libraries
            "com.typesafe.config.",
            "org.apache.commons.configuration.",

            // Utility libraries that might have conditional annotations
            "org.apache.commons.",
            "com.google.guava.",
            "org.apache.commons.lang.",
            "org.apache.commons.collections.",
            "org.apache.commons.io."
        )
    }

    /**
     * Gets the complete list of packages to exclude, combining defaults with additional excludes.
     */
    fun getAllExcludePackages(): List<String> = excludePackages + additionalExcludes

    /**
     * Checks if a package should be excluded from scanning.
     */
    fun shouldExcludePackage(packageName: String): Boolean {
        return getAllExcludePackages().any { exclude ->
            packageName.startsWith(exclude)
        }
    }

    /**
     * Checks if a package should be included in scanning.
     */
    fun shouldIncludePackage(packageName: String): Boolean {
        if (shouldExcludePackage(packageName)) {
            return false
        }

        return includePackages.any { include ->
            packageName.startsWith(include)
        }
    }
}
