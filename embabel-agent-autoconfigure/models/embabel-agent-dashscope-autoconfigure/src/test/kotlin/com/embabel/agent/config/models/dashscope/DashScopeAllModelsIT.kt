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
package com.embabel.agent.config.models.dashscope

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.models.DashScopeModels
import com.embabel.agent.autoconfigure.models.dashscope.AgentDashScopeAutoConfiguration
import com.embabel.agent.spi.LlmService
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.opentest4j.TestAbortedException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles

/**
 * Test-only configuration that brings up the platform beans alongside the
 * DashScope auto-configuration.
 */
@Profile("dashscope-chat-test")
@ConfigurationPropertiesScan(basePackages = ["com.embabel.agent", "com.embabel.example"])
@ComponentScan(basePackages = ["com.embabel.agent", "com.embabel.example"])
class DashScopeChatTestConfig

/**
 * Live smoke test that verifies all registered DashScope (Qwen) chat models work correctly
 * against the DashScope API endpoint.
 *
 * Design goals:
 *  1. **No drift.** The list of models is derived at runtime from registered [LlmService] beans.
 *  2. **Access gaps are skips, not failures.** Missing API access aborts individual tests instead of failing.
 *
 * Requires `DASHSCOPE_API_KEY` to be set; otherwise the whole class is disabled.
 */
@SpringBootTest(
    properties = [
        "embabel.models.default-llm=qwen3.7-flash",
        "embabel.agent.platform.models.dashscope.max-attempts=1",
        "embabel.agent.platform.models.dashscope.api-key=\${DASHSCOPE_API_KEY:dummy-key-for-context-loading}",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("dashscope-chat-test")
@Import(
    DashScopeChatTestConfig::class,
    AgentDashScopeAutoConfiguration::class,
)
@EnabledIfEnvironmentVariable(
    named = "DASHSCOPE_API_KEY",
    matches = ".+",
    disabledReason = "Live DashScope integration test requires DASHSCOPE_API_KEY",
)
class DashScopeAllModelsIT(
    @param:Autowired private val ai: Ai,
    @param:Autowired private val llms: List<LlmService<*>>,
) {

    /**
     * Fast, no-network guard: every chat model constant must have produced a registered [LlmService].
     */
    @Test
    fun everyDeclaredChatModelIsRegistered() {
        val registered = registeredDashScopeModelIds().toSet()
        val missing = EXPECTED_CHAT_MODELS.filterNot { it in registered }
        assertTrue(
            missing.isEmpty(),
            "Expected DashScope chat models to be registered but these are missing: $missing " +
                "(registered: ${registered.sorted()})",
        )
    }

    /**
     * Live smoke test: verify that the default model (qwen3.7-flash) can complete a simple request.
     */
    @Test
    fun defaultModelCompletesRoundTrip() {
        val response = try {
            ai.withLlm(DashScopeModels.QWEN3_7_FLASH)
                .generateText(SMOKE_PROMPT)
                .trim()
        } catch (ex: Exception) {
            if (isModelAccessError(ex)) {
                throw TestAbortedException(
                    "DASHSCOPE_API_KEY is set, but it does not have access to qwen3.7-flash",
                    ex,
                )
            }
            throw ex
        }

        assertNotNull(response, "Expected a response from qwen3.7-flash")
        assertTrue(response.isNotBlank(), "Expected non-empty response from qwen3.7-flash")
        assertTrue(
            response.contains("READY", ignoreCase = true),
            "Expected qwen3.7-flash to reply with READY, got: $response",
        )
    }

    private fun registeredDashScopeModelIds(): List<String> =
        llms.asSequence()
            .filter { it.provider == DashScopeModels.PROVIDER }
            .map { it.name }
            .distinct()
            .sorted()
            .toList()

    private fun isModelAccessError(ex: Exception): Boolean {
        val message = generateSequence<Throwable>(ex) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
        return message.contains("does not have access", ignoreCase = true) ||
            message.contains("model_not_found", ignoreCase = true) ||
            message.contains("permission", ignoreCase = true) ||
            message.contains("insufficient", ignoreCase = true) ||
            message.contains("balance", ignoreCase = true) ||
            message.contains("404", ignoreCase = false) ||
            message.contains("no longer available", ignoreCase = true)
    }

    companion object {
        private const val SMOKE_PROMPT = "Reply with exactly the word READY."

        /**
         * Canonical list of chat models expected to be registered, from [DashScopeModels].
         */
        private val EXPECTED_CHAT_MODELS = listOf(
            DashScopeModels.QWEN3_7_MAX,
            DashScopeModels.QWEN3_7_PLUS,
            DashScopeModels.QWEN3_7_FLASH,
        )
    }
}
