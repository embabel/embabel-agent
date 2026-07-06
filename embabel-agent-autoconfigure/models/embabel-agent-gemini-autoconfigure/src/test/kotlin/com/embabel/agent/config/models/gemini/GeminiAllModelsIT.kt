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
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.opentest4j.TestAbortedException
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.stream.Stream

/**
 * Live smoke test that exercises **every** registered Gemini (OpenAI-compatible) chat model to prove
 * that none of them crashes on a trivial round-trip.
 *
 * Sibling of `GoogleGenAiAllModelsIT` for the native GenAI path; this one covers the Gemini models
 * served through Google's OpenAI-compatible endpoint ([GeminiModels.PROVIDER] == "Google").
 *
 * Design goals (why this test looks the way it does):
 *
 *  1. **No drift.** The list of models actually called is derived at runtime from the registered
 *     [LlmService] beans (filtered by [GeminiModels.PROVIDER]), NOT from a hand-maintained list.
 *     Add a model to `gemini-models.yml` and it is covered automatically.
 *
 *  2. **Registration is guarded separately.** [everyDeclaredChatModelIsRegistered] cross-checks the
 *     registered beans against the canonical constants in [GeminiModels], so a model that silently
 *     fails to register is caught (it would otherwise just be absent from the live loop).
 *
 *  3. **Access gaps are skips, not failures.** A project that lacks entitlement to a preview model
 *     aborts that single case ([TestAbortedException] -> reported as skipped) instead of failing
 *     the whole suite. A genuine crash (bad request, serialization error, NPE, ...) still fails loudly.
 *
 * Requires `GEMINI_API_KEY` to be set; otherwise the whole class is disabled.
 */
@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gemini-2.5-flash",
        "embabel.agent.platform.models.gemini.max-attempts=1",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("gemini-chat-test")
@Import(GeminiChatTestConfig::class, AgentGeminiAutoConfiguration::class)
@EnabledIfEnvironmentVariable(
    named = "GEMINI_API_KEY",
    matches = ".+",
    disabledReason = "Live Gemini integration test requires GEMINI_API_KEY",
)
class GeminiAllModelsIT(
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
        val registered = registeredGeminiModelIds().toSet()
        val missing = EXPECTED_CHAT_MODELS.filterNot { it in registered }
        assertTrue(
            missing.isEmpty(),
            "Expected Gemini chat models to be registered but these are missing: $missing " +
                "(registered: ${registered.sorted()})",
        )
    }

    /**
     * One live test per registered Gemini chat model. Fails only if a model genuinely misbehaves;
     * skips (aborts) when the project lacks access to that model.
     */
    @TestFactory
    fun everyRegisteredModelRepliesWithoutCrashing(): Stream<DynamicTest> {
        val modelIds = registeredGeminiModelIds()
        assertTrue(
            modelIds.isNotEmpty(),
            "Expected at least one Gemini model to be registered, found none",
        )

        return modelIds.stream().map { modelId ->
            DynamicTest.dynamicTest("gemini model '$modelId' replies without crashing") {
                val response = try {
                    ai.withLlm(modelId).generateText(SMOKE_PROMPT).trim()
                } catch (ex: Exception) {
                    if (isModelAccessError(ex)) {
                        throw TestAbortedException(
                            "GEMINI_API_KEY is set, but the configured Google project does not have access to $modelId",
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

    /** All Gemini chat models currently registered as [LlmService] beans, sorted for stable ordering. */
    private fun registeredGeminiModelIds(): List<String> =
        llms.asSequence()
            .filter { it.provider == GeminiModels.PROVIDER }
            .map { it.name }
            .distinct()
            .sorted()
            .toList()

    private fun isModelAccessError(ex: Exception): Boolean {
        val message = generateSequence<Throwable>(ex) { it.cause }
            .mapNotNull { it.message }
            .joinToString(" | ")
        return message.contains("does not have access to model", ignoreCase = true) ||
            message.contains("model_not_found", ignoreCase = true) ||
            message.contains("PERMISSION_DENIED", ignoreCase = true) ||
            message.contains("404", ignoreCase = false) ||
            message.contains("no longer available", ignoreCase = true)
    }

    companion object {
        private const val SMOKE_PROMPT = "Reply with exactly the word READY."

        /**
         * Canonical list of chat models this module is expected to register, taken straight from
         * [GeminiModels]. The retired 2.0 models are intentionally excluded (shut down by Google on
         * 2026-06-01) and the embedding model is not a chat LLM.
         */
        private val EXPECTED_CHAT_MODELS = listOf(
            GeminiModels.GEMINI_3_5_FLASH,
            GeminiModels.GEMINI_3_1_FLASH_LITE,
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
