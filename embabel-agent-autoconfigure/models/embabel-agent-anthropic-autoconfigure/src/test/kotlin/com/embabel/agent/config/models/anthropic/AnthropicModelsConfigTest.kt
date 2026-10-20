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
package com.embabel.agent.config.models.anthropic

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.autoconfig.LlmAutoConfigMetadataLoader
import io.micrometer.observation.ObservationRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.web.client.RestClient

class AnthropicModelsConfigTest {

    private class Built(val options: AnthropicChatOptions, val warnings: List<String>)

    private fun build(modelDef: AnthropicModelDefinition): Built {
        val logger = LoggerFactory.getLogger(AnthropicModelsConfig::class.java) as Logger
        val logs = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(logs)
        try {
            val beanFactory = DefaultListableBeanFactory()
            AnthropicModelsConfig(
                envBaseUrl = null,
                envApiKey = "test-key",
                properties = AnthropicProperties(),
                observationRegistry = beanFactory.getBeanProvider(ObservationRegistry::class.java),
                restClientBuilder = beanFactory.getBeanProvider(RestClient.Builder::class.java),
                configurableBeanFactory = beanFactory,
                modelLoader = object : LlmAutoConfigMetadataLoader<AnthropicModelDefinitions> {
                    override fun loadAutoConfigMetadata() = AnthropicModelDefinitions(models = listOf(modelDef))
                },
            ).anthropicModelsInitializer()
            val llm = beanFactory.getBean(modelDef.name) as SpringAiLlmService
            val options = llm.chatModel.defaultOptions as AnthropicChatOptions
            return Built(options, logs.list.filter { it.level == Level.WARN }.map { it.formattedMessage })
        } finally {
            logger.detachAppender(logs)
        }
    }

    @Test
    fun `restricted model drops configured sampling with one warning naming model and values`() {
        val built = build(
            AnthropicModelDefinition(
                name = "sonnet55", modelId = "claude-sonnet-5-5", temperature = 0.3, topP = 0.9, topK = 40,
            )
        )
        assertNull(built.options.temperature)
        assertNull(built.options.topP)
        assertNull(built.options.topK)
        assertEquals(1, built.warnings.size, "${built.warnings}")
        val warning = built.warnings.single()
        assertTrue(
            warning.contains("claude-sonnet-5-5") && warning.contains("temperature=0.3") &&
                warning.contains("topP=0.9") && warning.contains("topK=40")
        ) { warning }
    }

    @Test
    fun `restricted model with default temperature drops it without warning`() {
        val built = build(AnthropicModelDefinition(name = "sonnet55", modelId = "claude-sonnet-5-5", temperature = 1.0))
        assertNull(built.options.temperature)
        assertTrue(built.warnings.isEmpty()) { "${built.warnings}" }
    }

    @Test
    fun `restricted model without sampling configured does not warn`() {
        val built = build(AnthropicModelDefinition(name = "sonnet55", modelId = "claude-sonnet-5-5"))
        assertTrue(built.warnings.isEmpty()) { "${built.warnings}" }
    }

    @Test
    fun `older model passes sampling through without warning`() {
        val built = build(
            AnthropicModelDefinition(
                name = "sonnet45", modelId = "claude-sonnet-4-5", temperature = 0.3, topP = 0.9, topK = 40,
            )
        )
        assertEquals(0.3, built.options.temperature)
        assertEquals(0.9, built.options.topP)
        assertEquals(40, built.options.topK)
        assertTrue(built.warnings.isEmpty()) { "${built.warnings}" }
    }
}
