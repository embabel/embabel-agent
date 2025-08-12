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

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.PropertySource
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import jakarta.annotation.PostConstruct

/**
 * Loads agent platform properties into Spring's Environment for library-wide availability.
 *
 * This configuration class uses pure Spring Framework features (@PropertySource, @Order)
 * to ensure compatibility with both Spring Boot and pure Spring applications.
 *
 * ## Design Rationale
 *
 * ### Library-Friendly Approach
 * - **@PropertySource**: Core Spring Framework feature (since 3.1) - works in any Spring environment
 * - **@Order(HIGHEST_PRECEDENCE)**: Ensures properties are loaded before other @Configuration classes
 * - **No Spring Boot dependencies**: Works with Spring WebMVC, WebFlux, standalone contexts
 *
 * ### Processing Order Guarantee
 * ```
 * 1. AgentPlatformPropertiesLoader processes first → Properties loaded into Environment
 * 2. Other @Configuration classes process → Properties available for @ConfigurationProperties binding
 * 3. @ConfigurationProperties beans created → Automatic property binding occurs
 * ```
 *
 * ### Property Binding Flow
 * - **agent-platform.properties** → Spring Environment → **@ConfigurationProperties classes**
 * - Enables: AnthropicProperties, OpenAiProperties, AgentPlatformProperties, Migration system configs
 *
 * ## Usage Impact
 *
 * After this loader is active:
 * - **AnthropicProperties**: Will bind from `embabel.agent.platform.models.anthropic.*` (no longer defaults)
 * - **OpenAiProperties**: Will bind from `embabel.agent.platform.models.openai.*` (no longer defaults)
 * - **Migration system**: Properties will be loaded for conditional bean creation
 * - **AgentPlatformProperties**: Will bind actual values instead of being dormant
 *
 * @since 1.x
 */
@Configuration
@PropertySource("classpath:agent-platform.properties")
@Order(Ordered.HIGHEST_PRECEDENCE)
class AgentPlatformPropertiesLoader {

    private val logger = LoggerFactory.getLogger(AgentPlatformPropertiesLoader::class.java)

    @PostConstruct
    fun init() {
        logger.info("Agent platform properties loaded from classpath:agent-platform.properties")
    }
}
