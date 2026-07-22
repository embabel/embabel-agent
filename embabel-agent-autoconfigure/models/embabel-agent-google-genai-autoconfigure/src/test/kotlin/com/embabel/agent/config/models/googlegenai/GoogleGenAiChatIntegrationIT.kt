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
package com.embabel.agent.config.models.googlegenai

import com.embabel.agent.api.annotation.LlmTool
import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.common.createObjectIfPossible
import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.autoconfigure.models.googlegenai.AgentGoogleGenAiAutoConfiguration
import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
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

@Profile("google-genai-chat-test")
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
class GoogleGenAiChatTestConfig

@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gemini-2.5-flash",
        "embabel.agent.platform.models.googlegenai.max-attempts=1",
        "embabel.agent.platform.models.googlegenai.api-key=\${GOOGLE_API_KEY:\${GEMINI_API_KEY:dummy-key-for-context-loading}}",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("google-genai-chat-test")
@Import(GoogleGenAiChatTestConfig::class, AgentGoogleGenAiAutoConfiguration::class)
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+", disabledReason = "Integration test requires GOOGLE_API_KEY")
class GoogleGenAiChatIntegrationIT(
    @param:Autowired private val ai: Ai,
    @param:Autowired private val llms: List<LlmService<*>>,
    @param:Autowired private val applicationContext: ApplicationContext,
) {

    data class MonthItem(
        val name: String,
        val temperature: Int?,
    )

    @Test
    fun `registers all google genai model beans`() {
        val expectedBeans = listOf(
            "gemini_3_5_flash",
            "gemini_3_1_pro_preview",
            "gemini_3_1_pro_preview_customtools",
            "gemini_3_flash_preview",
            "gemini_3_1_flash_lite",
            "gemini_3_1_flash_lite_preview",
            "gemini_25_pro",
            "gemini_25_flash",
            "gemini_25_flash_lite",
        )
        expectedBeans.forEach { beanName ->
            assertTrue(applicationContext.containsBean(beanName), "Expected bean '$beanName' to be registered")
        }
    }

    @ParameterizedTest(name = "calls real Google GenAI API for {0}")
    @MethodSource("allGoogleGenAiModelIds")
    fun `calls real Google GenAI API for model`(modelId: String) {
        val llm = llms.find { it.name == modelId }
        assertNotNull(llm, "Expected LLM service to be registered for $modelId")

        val response = runGoogleCall(modelId) {
            ai.withLlm(modelId).generateText("Reply with exactly the word READY.").trim()
        }

        assertTrue(response.isNotBlank(), "Expected non-empty response from $modelId")
        assertTrue(response.contains("READY", ignoreCase = true), "Expected $modelId to reply with READY, got: $response")
    }

    @Test
    fun `supports thinking mode for google genai prompt runner`() {
        val runner = ai.withLlm(
            LlmOptions(GoogleGenAiModels.GEMINI_2_5_FLASH)
                .withThinking(Thinking.withExtraction())
        )

        assertTrue(runner.supportsThinking(), "Expected Google GenAI prompt runner to advertise thinking support")
    }

    @Test
    fun `creates typed object through google genai thinking mode`() {
        // Prepare
        val modelId = GoogleGenAiModels.GEMINI_2_5_FLASH
        val runner = ai.withLlm(
            LlmOptions(modelId)
                .withThinking(Thinking.withExtraction())
        )

        // Execute
        val response = runGoogleCall(modelId) {
            runner.thinking().createObject(
                """
                Think through the climate question carefully, then return a JSON object.
                What is typically the hottest month in Florida and an approximate average high temperature in Fahrenheit?
                Return:
                - name: the month name
                - temperature: an integer temperature in Fahrenheit
                """.trimIndent(),
                MonthItem::class.java,
            )
        }

        // Verify
        assertNotNull(response, "Expected Google thinking mode to return a ThinkingResponse")
        assertTrue(response.hasResult(), "Expected Google thinking mode to return a typed result")
        assertNotNull(response.thinkingBlocks, "Expected Google thinking mode to always expose thinking blocks collection")

        val result = requireNotNull(response.result) { "Expected non-null MonthItem result from Google thinking mode" }
        assertTrue(result.name.lowercase() in setOf("july", "august"), "Expected a plausible hottest-month result from the model, got: ${result.name}")
        assertNotNull(result.temperature, "Expected Google thinking mode to populate the temperature field")

        val temperature = requireNotNull(result.temperature)
        assertTrue(temperature in 70..110, "Expected a plausible Fahrenheit high for Florida summer, got: $temperature")
    }

    /**
     * Regression integration tests for Google GenAI multi-part thinking and structured output.
     *
     * These live tests lock regression-safety contracts for Google GenAI multi-part thinking
     * and structured output after #1691 / non-thought generation selection.
     *
     * Requires GEMINI_API_KEY (class-level EnabledIfEnvironmentVariable).
     */
    @Nested
    @DisplayName("Google GenAI multi-gen / thinking regression ITs")
    inner class GoogleGenAiRegressionITs {

        @Test
        fun `createObject without thinking returns typed MonthItem`() {
            // Prepare
            val modelId = GoogleGenAiModels.GEMINI_2_5_FLASH
            val runner = ai.withLlm(modelId)

            // Execute
            val result = runGoogleCall(modelId) {
                runner.createObject(
                    """
                    Return a JSON object only (no markdown).
                    What is typically the hottest month in Florida and an approximate average high temperature in Fahrenheit?
                    Fields: name (month), temperature (integer Fahrenheit).
                    """.trimIndent(),
                    MonthItem::class.java,
                )
            }

            // Verify
            assertNotNull(result)
            assertTrue(result.name.lowercase() in setOf("july", "august"), "Expected plausible month, got: ${result.name}",)
            assertNotNull(result.temperature)
            assertTrue(result.temperature!! in 70..110, "Expected plausible temp, got: ${result.temperature}")
        }

        @Test
        fun `thinking generateText returns non-blank answer text`() {
            // Prepare
            val modelId = GoogleGenAiModels.GEMINI_2_5_FLASH
            val runner = ai.withLlm(
                LlmOptions(modelId).withThinking(Thinking.withExtraction())
            )

            // Execute
            val response = runGoogleCall(modelId) {
                runner.thinking().generateText(
                    "Think briefly, then reply with exactly the single word READY and nothing else."
                )
            }

            // Verify
            assertNotNull(response)
            assertTrue(response.hasResult(), "thinking generateText must expose a result")
            val text = requireNotNull(response.result).trim()
            assertTrue(text.isNotBlank(), "thinking generateText must not yield blank after multi-gen selection")
            assertTrue(text.contains("READY", ignoreCase = true), "Expected READY in answer text, got: $text",)
        }

        @Test
        fun `thinking createObjectIfPossible returns ThinkingResponse`() {
            // Prepare
            val modelId = GoogleGenAiModels.GEMINI_2_5_FLASH
            val runner = ai.withLlm(
                LlmOptions(modelId).withThinking(Thinking.withExtraction())
            )

            // Execute
            val response = runGoogleCall(modelId) {
                runner.thinking().createObjectIfPossible(
                    """
                    Think about the coldest month in Alaska and its approximate average low in Fahrenheit.
                    If possible return JSON with name and temperature; otherwise indicate impossibility.
                    """.trimIndent(),
                    MonthItem::class.java,
                )
            }

            // Verify
            assertNotNull(response, "ThinkingResponse must not be null")
            assertNotNull(response.thinkingBlocks, "thinkingBlocks collection must be non-null")
            response.result?.let { item ->
                assertNotNull(item.name)
                assertFalse(item.name.isBlank())
            }
        }

        @Test
        fun `thinking createObject with tool object completes with typed result`() {
            // Prepare
            val modelId = GoogleGenAiModels.GEMINI_2_5_FLASH
            val runner = ai.withLlm(
                LlmOptions(modelId).withThinking(Thinking.withExtraction())
            ).withToolObject(SimpleConversionTooling())

            // Execute
            val response = runGoogleCall(modelId) {
                runner.thinking().createObject(
                    """
                    Think carefully, use tools if useful, then return JSON only.
                    What is typically the hottest month in Florida and an approximate average high temperature in Fahrenheit?
                    Fields: name (month name), temperature (integer Fahrenheit).
                    """.trimIndent(),
                    MonthItem::class.java,
                )
            }

            // Verify
            assertNotNull(response)
            assertTrue(response.hasResult(), "Expected typed result with tools + thinking")
            val result = requireNotNull(response.result)
            assertTrue(result.name.lowercase() in setOf("july", "august"), "Expected plausible month, got: ${result.name}",)
            assertNotNull(result.temperature)
        }
    }

    /** Minimal tool surface for thinking + tools regression IT. */
    class SimpleConversionTooling {
        /**
         * Provide a deterministic arithmetic tool that Gemini can call while thinking is
         * enabled. The test only needs a simple tool-call continuation path, not domain logic.
         */
        @LlmTool(description = "Convert Celsius to Fahrenheit integer approximation")
        fun celsiusToFahrenheit(celsius: Int): Int = (celsius * 9 / 5) + 32
    }

    /**
     * Execute a live Google call while converting provider access errors into skipped tests.
     *
     * Some configured API keys can authenticate but lack access to newer preview model ids.
     * Treating that case as an aborted test keeps the integration suite focused on code
     * regressions when the environment is otherwise valid.
     */
    private fun <T> runGoogleCall(modelId: String, block: () -> T): T =
        try {
            block()
        } catch (ex: Exception) {
            if (isModelAccessError(ex)) {
                throw TestAbortedException("GOOGLE_API_KEY is set, but the configured Google project does not have access to $modelId", ex)
            }
            throw ex
        }

    /**
     * Detect the provider error emitted when credentials are valid but the selected Gemini
     * model is not available to the configured Google project.
     */
    private fun isModelAccessError(ex: Exception): Boolean {
        val message = generateSequence<Throwable>(ex) { it.cause }.mapNotNull { it.message }.joinToString(" | ")
        return message.contains("does not have access to model", ignoreCase = true)
            || message.contains("model_not_found", ignoreCase = true)
            || message.contains("PERMISSION_DENIED", ignoreCase = true)
            || message.contains("404", ignoreCase = false)
            || message.contains("no longer available", ignoreCase = true)
    }

    companion object {
        @JvmStatic
        fun allGoogleGenAiModelIds(): List<String> = listOf(
            GoogleGenAiModels.GEMINI_3_5_FLASH,
            GoogleGenAiModels.GEMINI_3_1_PRO_PREVIEW,
            GoogleGenAiModels.GEMINI_3_1_PRO_PREVIEW_CUSTOMTOOLS,
            GoogleGenAiModels.GEMINI_3_FLASH_PREVIEW,
            GoogleGenAiModels.GEMINI_3_1_FLASH_LITE,
            GoogleGenAiModels.GEMINI_3_1_FLASH_LITE_PREVIEW,
            GoogleGenAiModels.GEMINI_2_5_PRO,
            GoogleGenAiModels.GEMINI_2_5_FLASH,
            GoogleGenAiModels.GEMINI_2_5_FLASH_LITE,
        )
    }
}
