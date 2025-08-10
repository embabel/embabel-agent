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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

/**
 * Tests for DeprecatedPropertyScanningConfig configuration binding and logic.
 *
 * ## Test Strategy for Default Values
 *
 * This test suite uses @TestPropertySource to override scanning defaults for stable,
 * predictable test behavior regardless of production default changes.
 *
 * **Production Default (as of Iteration 1)**: enabled = true
 * **Test Override**: We explicitly control values via @TestPropertySource
 *
 * This approach ensures:
 * - Tests remain stable when production defaults change
 * - Clear separation between test expectations and production behavior
 * - No need to update tests when toggling production scanning on/off
 */
@SpringBootTest(classes = [DeprecatedPropertyScanningConfigIntegrationTest.TestConfiguration::class])
@TestPropertySource(properties = [
    "embabel.agent.platform.migration.scanning.enabled=true",
    "embabel.agent.platform.migration.scanning.auto-exclude-jar-packages=false",
    "embabel.agent.platform.migration.scanning.max-scan-depth=5",
    "embabel.agent.platform.migration.scanning.include-packages[0]=com.example.test",
    "embabel.agent.platform.migration.scanning.include-packages[1]=com.example.custom",
    "embabel.agent.platform.migration.scanning.additional-excludes[0]=com.example.excluded",
    "embabel.agent.platform.migration.scanning.additional-excludes[1]=org.example.test"
])
class DeprecatedPropertyScanningConfigIntegrationTest {

    @Autowired
    private lateinit var config: DeprecatedPropertyScanningConfig

    @Test
    fun `should bind scanning configuration properties correctly`() {
        assertThat(config.enabled).isTrue()
        assertThat(config.autoExcludeJarPackages).isFalse()
        assertThat(config.maxScanDepth).isEqualTo(5)

        assertThat(config.includePackages).containsExactly(
            "com.example.test",
            "com.example.custom"
        )

        assertThat(config.additionalExcludes).containsExactly(
            "com.example.excluded",
            "org.example.test"
        )
    }


    @Test
    fun `should have comprehensive default exclude packages`() {
        val defaultExcludes = DeprecatedPropertyScanningConfig.defaultExcludePackages()

        // Verify key framework packages are excluded
        assertThat(defaultExcludes).contains(
            "java.",
            "javax.",
            "kotlin.",
            "kotlinx.",
            "org.springframework.",
            "org.springframework.boot.",
            "com.fasterxml.jackson.",
            "org.slf4j.",
            "ch.qos.logback.",
            "io.micrometer.",
            "org.apache.",
            "com.google.guava."
        )

        // Should be comprehensive - at least 50+ entries
        assertThat(defaultExcludes.size).isGreaterThan(50)
    }


    @Test
    fun `should combine default and additional excludes correctly`() {
        val allExcludes = config.getAllExcludePackages()

        // Should include defaults
        assertThat(allExcludes).contains("java.", "org.springframework.")

        // Should include additional excludes from test properties
        assertThat(allExcludes).contains("com.example.excluded", "org.example.test")
    }

    @Test
    fun `shouldExcludePackage should work correctly`() {
        // Framework packages should be excluded
        assertThat(config.shouldExcludePackage("java.lang.String")).isTrue()
        assertThat(config.shouldExcludePackage("org.springframework.boot.Application")).isTrue()
        assertThat(config.shouldExcludePackage("kotlin.collections.List")).isTrue()

        // Additional excludes should work
        assertThat(config.shouldExcludePackage("com.example.excluded.SomeClass")).isTrue()
        assertThat(config.shouldExcludePackage("org.example.test.TestClass")).isTrue()

        // User packages should not be excluded
        assertThat(config.shouldExcludePackage("com.example.test.UserClass")).isFalse()
        assertThat(config.shouldExcludePackage("com.embabel.agent.MyService")).isFalse()
    }

    @Test
    fun `shouldIncludePackage should work correctly`() {
        // Included packages should be included
        assertThat(config.shouldIncludePackage("com.example.test.SomeClass")).isTrue()
        assertThat(config.shouldIncludePackage("com.example.custom.MyClass")).isTrue()

        // Excluded packages should not be included even if they match include pattern
        assertThat(config.shouldIncludePackage("com.example.excluded.ExcludedClass")).isFalse()

        // Non-matching packages should not be included
        assertThat(config.shouldIncludePackage("com.other.package.SomeClass")).isFalse()

        // Framework packages should not be included
        assertThat(config.shouldIncludePackage("org.springframework.boot.Application")).isFalse()
    }

    @Test
    fun `should handle edge cases in package matching`() {
        // Exact prefix matches
        assertThat(config.shouldExcludePackage("java")).isFalse() // Should require dot
        assertThat(config.shouldExcludePackage("java.")).isTrue()
        assertThat(config.shouldExcludePackage("java.lang")).isTrue()

        // Empty package names
        assertThat(config.shouldExcludePackage("")).isFalse()
        assertThat(config.shouldIncludePackage("")).isFalse()
    }

    @EnableConfigurationProperties(DeprecatedPropertyScanningConfig::class)
    class TestConfiguration
}
