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
package com.embabel.agent.config.models.zai

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.models.ZaiModels
import com.embabel.agent.autoconfigure.models.zai.AgentZaiAutoConfiguration
import com.embabel.agent.spi.LlmService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.Thinking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.opentest4j.TestAbortedException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.test.context.ActiveProfiles
import java.util.stream.Stream

/**
 * Test-only configuration that brings up the platform beans (including [Ai]) alongside the
 * Z.ai auto-configuration, so the live smoke test can resolve a real prompt runner.
 */
@Profile("zai-chat-test")
@ConfigurationPropertiesScan(basePackages = ["com.embabel.agent", "com.embabel.example"])
@ComponentScan(basePackages = ["com.embabel.agent", "com.embabel.example"])
class ZaiChatTestConfig

/**
 * Live smoke test that exercises **every** registered Z.ai (Zhipu GLM) chat model to prove
 * that none of them crashes on a trivial round-trip against the native ZhiPuAI endpoint.
 *
 * Design goals (mirrors `GoogleGenAiAllModelsIT`):
 *
 *  1. **No drift.** The list of models actually called is derived at runtime from the registered
 *     [LlmService] beans (filtered by [ZaiModels.PROVIDER]), NOT from a hand-maintained list.
 *     Add a model to `zai-models.yml` and it is covered automatically.
 *
 *  2. **Registration is guarded separately.** [everyDeclaredChatModelIsRegistered] cross-checks the
 *     registered beans against the canonical constants in [ZaiModels], so a model that silently
 *     fails to register is caught (it would otherwise just be absent from the live loop).
 *
 *  3. **Access gaps are skips, not failures.** A key that lacks entitlement (or balance) for a
 *     model aborts that single case ([TestAbortedException] -> reported as skipped) instead of
 *     failing the whole suite. A genuine crash (bad request, serialization error, NPE, ...) still
 *     fails loudly.
 *
 * Requires `ZAI_API_KEY` to be set; otherwise the whole class is disabled.
 */
@SpringBootTest(
    properties = [
        "embabel.models.default-llm=glm-4.7-flash",
        "embabel.agent.platform.models.zai.max-attempts=1",
        "embabel.agent.platform.models.zai.api-key=\${ZAI_API_KEY:dummy-key-for-context-loading}",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("zai-chat-test")
@Import(
    ZaiChatTestConfig::class,
    AgentZaiAutoConfiguration::class,
)
@EnabledIfEnvironmentVariable(
    named = "ZAI_API_KEY",
    matches = ".+",
    disabledReason = "Live Z.ai integration test requires ZAI_API_KEY",
)
class ZaiAllModelsIT(
    @param:Autowired private val ai: Ai,
    @param:Autowired private val llms: List<LlmService<*>>,
) {

    /**
     * Fast, no-network guard: every chat model we ship a constant for must have produced a
     * registered [LlmService]. Catches registration failures and YAML/constant drift before we
     * ever spend a token.
     */
    @Test
    fun everyDeclaredChatModelIsRegistered() {
        val registered = registeredZaiModelIds().toSet()
        val missing = EXPECTED_CHAT_MODELS.filterNot { it in registered }
        assertTrue(
            missing.isEmpty(),
            "Expected Z.ai chat models to be registered but these are missing: $missing " +
                "(registered: ${registered.sorted()})",
        )
    }

    /**
     * No-network guard: reasoning-capable GLM models must advertise thinking support so the
     * native reasoning path is actually wired, not silently dropped.
     */
    @Test
    fun reasoningModelsAdvertiseThinkingSupport() {
        val runner = ai.withLlm(
            LlmOptions(ZaiModels.GLM_4_7).withThinking(Thinking.withExtraction())
        )
        assertTrue(runner.supportsThinking(), "Expected Z.ai prompt runner to advertise thinking support")
    }

    /**
     * One live test per registered Z.ai chat model. Fails only if a model genuinely misbehaves;
     * skips (aborts) when the key lacks access to that model.
     */
    @TestFactory
    fun everyRegisteredModelRepliesWithoutCrashing(): Stream<DynamicTest> {
        val modelIds = registeredZaiModelIds()
        assertTrue(
            modelIds.isNotEmpty(),
            "Expected at least one Z.ai model to be registered, found none",
        )

        return modelIds.stream().map { modelId ->
            DynamicTest.dynamicTest("GLM model '$modelId' replies without crashing") {
                val response = try {
                    ai.withLlm(modelId).generateText(SMOKE_PROMPT).trim()
                } catch (ex: Exception) {
                    if (isModelAccessError(ex)) {
                        throw TestAbortedException(
                            "ZAI_API_KEY is set, but it does not have access to (or balance for) $modelId",
                            ex,
                        )
                    }
                    throw ex
                }

                assertNotNull(response, "Expected a response object from $modelId")
                assertTrue(response.isNotBlank(), "Expected non-empty response from $modelId")
                assertTrue(
                    response.contains("READY", ignoreCase = true),
                    "Expected $modelId to reply with READY, got: $response",
                )
            }
        }
    }

    /** All Z.ai chat models currently registered as [LlmService] beans, sorted for stable ordering. */
    private fun registeredZaiModelIds(): List<String> =
        llms.asSequence()
            .filter { it.provider == ZaiModels.PROVIDER }
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
         * Canonical list of chat models this module is expected to register, taken straight from
         * [ZaiModels].
         */
        private val EXPECTED_CHAT_MODELS = listOf(
            ZaiModels.GLM_5_2,
            ZaiModels.GLM_4_7,
            ZaiModels.GLM_4_6,
            ZaiModels.GLM_4_5_AIR,
            ZaiModels.GLM_4_7_FLASH,
        )
    }
}
