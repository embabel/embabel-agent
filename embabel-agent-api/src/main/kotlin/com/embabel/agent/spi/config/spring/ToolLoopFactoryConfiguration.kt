/*
 * Copyright 2024-2026 Embabel Pty Ltd.
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
package com.embabel.agent.spi.config.spring

import com.embabel.agent.api.tool.config.ToolLoopConfiguration
import com.embabel.agent.spi.loop.ToolLoopFactory
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Configuration for ToolLoop.
 *
 * Creates a [ToolLoopFactory] bean based on [ToolLoopConfiguration] properties.
 */
@Configuration
@EnableConfigurationProperties(ToolLoopConfiguration::class)
class ToolLoopFactoryConfiguration(
    private val config: ToolLoopConfiguration,
) {

    private val logger = LoggerFactory.getLogger(ToolLoopFactoryConfiguration::class.java)

    init {
        logger.info("ToolLoop configured with type: {}", config.type)
    }

    @Bean
    fun toolLoopFactory(): ToolLoopFactory {
        logger.info("Creating ToolLoopFactory with type: {}", config.type)
        return ToolLoopFactory.withConfig(config)
    }
}
