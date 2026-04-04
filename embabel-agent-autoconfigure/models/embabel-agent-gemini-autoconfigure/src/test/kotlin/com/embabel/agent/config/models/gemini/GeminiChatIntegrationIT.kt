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
package com.embabel.agent.config.models.gemini

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.models.GeminiModels
import com.embabel.agent.autoconfigure.models.gemini.AgentGeminiAutoConfiguration
import com.embabel.agent.spi.LlmService
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.opentest4j.TestAbortedException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles

@Profile("gemini-chat-test")
@ConfigurationPropertiesScan(
    basePackages = [
        "com.embabel.agent",
        "com.embabel.example",
    ]
)
@ComponentScan(
    basePackages = [
        "com.embabel.agent",
        "com.embabel.example",
    ]
)
class GeminiChatTestConfig

@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gemini-2.5-flash",
        "embabel.agent.platform.models.gemini.max-attempts=1",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("gemini-chat-test")
@Import(GeminiChatTestConfig::class, AgentGeminiAutoConfiguration::class)
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+", disabledReason = "Integration test requires GEMINI_API_KEY")
class GeminiChatIntegrationIT(
    @param:Autowired private val ai: Ai,
    @param:Autowired private val llms: List<LlmService<*>>,
    @param:Autowired private val applicationContext: ApplicationContext,
) {

    @Test
    fun `registers all gemini model beans`() {
        val expectedBeans = listOf(
            "gemini_3_1_pro_preview",
            "gemini_3_1_pro_preview_customtools",
            "gemini_3_1_flash_lite_preview",
            "gemini_3_flash_preview",
            "gemini_25_pro",
            "gemini_25_flash",
            "gemini_25_flash_lite",
        )
        expectedBeans.forEach { beanName ->
            assertTrue(applicationContext.containsBean(beanName), "Expected bean '$beanName' to be registered")
        }
    }

    @ParameterizedTest(name = "calls real Gemini API for {0}")
    @MethodSource("allGeminiModelIds")
    fun `calls real Gemini API for model`(modelId: String) {
        val llm = llms.find { it.name == modelId }
        assertNotNull(llm, "Expected LLM service to be registered for $modelId")

        val response = try {
            ai.withLlm(modelId).generateText("Reply with exactly the word READY.").trim()
        } catch (ex: Exception) {
            if (isModelAccessError(ex)) {
                throw TestAbortedException("GEMINI_API_KEY is set, but the configured Google project does not have access to $modelId", ex)
            }
            throw ex
        }

        assertTrue(response.isNotBlank(), "Expected non-empty response from $modelId")
        assertTrue(response.contains("READY", ignoreCase = true), "Expected $modelId to reply with READY, got: $response")
    }

    private fun isModelAccessError(ex: Exception): Boolean {
        val message = generateSequence<Throwable>(ex) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        return message.contains("does not have access to model", ignoreCase = true)
            || message.contains("model_not_found", ignoreCase = true)
            || message.contains("PERMISSION_DENIED", ignoreCase = true)
            || message.contains("404", ignoreCase = false)
    }

    companion object {
        @JvmStatic
        fun allGeminiModelIds(): List<String> = listOf(
            GeminiModels.GEMINI_3_1_PRO_PREVIEW,
            GeminiModels.GEMINI_3_1_PRO_PREVIEW_CUSTOMTOOLS,
            GeminiModels.GEMINI_3_1_FLASH_LITE_PREVIEW,
            GeminiModels.GEMINI_3_FLASH_PREVIEW,
            GeminiModels.GEMINI_2_5_PRO,
            GeminiModels.GEMINI_2_5_FLASH,
            GeminiModels.GEMINI_2_5_FLASH_LITE,
        )
    }
}
