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
 * Configuration for deprecated property warning behavior.
 *
 * ## Spring Boot + Kotlin Binding Pattern Analysis
 *
 * This configuration class uses `var` for the Boolean property due to **production requirements**
 * discovered when using `@Configuration` classes with CGLIB proxying.
 *
 * ### Why `var individualLogging: Boolean = false`? - PRODUCTION LESSON LEARNED
 *
 * **CGLIB Proxying Requirement**: When using `@Configuration` (not just `@ConfigurationProperties`),
 * Spring Boot creates CGLIB proxies that require setters for environment variable binding,
 * **even for scalar types**. Using `val` causes: `"No setter found for property: individual-logging"`
 *
 * **Production Error**:
 * ```
 * Failed to bind properties under 'embabel.agent.platform.migration.warnings'
 * Property: embabel.agent.platform.migration.warnings.individual-logging
 * Value: "true"
 * Origin: System Environment Property "EMBABEL_AGENT_PLATFORM_MIGRATION_WARNINGS_INDIVIDUAL_LOGGING"
 * Reason: java.lang.IllegalStateException: No setter found for property: individual-logging
 * ```
 *
 * **Key Distinction**: Pure `@ConfigurationProperties` data classes can use `val` with constructor binding,
 * but `@Configuration` + `@ConfigurationProperties` classes need `var` for CGLIB proxy compatibility.
 *
 * ### Expected Usage Pattern:
 * ```bash
 * # Environment variable binding (requires var with @Configuration classes)
 * export EMBABEL_AGENT_PLATFORM_MIGRATION_WARNINGS_INDIVIDUAL_LOGGING=true
 * ```
 *
 * ### Spring Boot Property Binding Reality Check:
 * - **⚠️ Scalar types with @Configuration**: `var` required for CGLIB proxy compatibility
 * - **❌ Complex types (List, Map)**: `var` required for reliable environment variable binding
 * - **✅ Constructor Binding**: Kotlin data classes automatically use constructor binding for `val`
 * - **✅ Setter Binding**: Properties with `var` use setter-based binding
 *
 * **Production Decision**: Uses `var` due to CGLIB proxy requirements with @Configuration annotation.
 *
 * @see AgentPlatformPropertiesIntegrationTest for comprehensive val vs var binding documentation
 * @see DeprecatedPropertyScanningConfig for the real reason var is needed (List properties)
 */
@Configuration
@ConfigurationProperties("embabel.agent.platform.migration.warnings")
@ConditionalOnProperty(
    name = ["embabel.agent.platform.migration.warnings.enabled"],
    havingValue = "true",
    matchIfMissing = true
)
data class DeprecatedPropertyWarningConfig(
    /**
     * Whether to enable individual warning logging.
     * **Enabled by default** for maximum visibility during migration periods.
     * When true, each deprecated property usage is logged immediately.
     * When false, only aggregated summary is logged.
     */
    var individualLogging: Boolean = true
)
