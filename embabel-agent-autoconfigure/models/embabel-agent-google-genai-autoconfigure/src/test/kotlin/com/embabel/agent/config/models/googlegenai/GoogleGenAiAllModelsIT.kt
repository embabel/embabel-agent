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

import com.embabel.agent.api.common.Ai
import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.autoconfigure.models.googlegenai.AgentGoogleGenAiAutoConfiguration
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
 * Live smoke test that exercises **every** registered Google GenAI (Gemini) chat model to prove
 * that none of them crashes on a trivial round-trip.
 *
 * Design goals (why this test looks the way it does):
 *
 *  1. **No drift.** The list of models actually called is derived at runtime from the registered
 *     [LlmService] beans (filtered by [GoogleGenAiModels.PROVIDER]), NOT from a hand-maintained
 *     list. Add a model to `google-genai-models.yml` and it is covered automatically.
 *
 *  2. **Registration is guarded separately.** [everyDeclaredChatModelIsRegistered] cross-checks the
 *     registered beans against the canonical constants in [GoogleGenAiModels], so a model that
 *     silently fails to register is caught (it would otherwise just be absent from the live loop).
 *
 *  3. **Access gaps are skips, not failures.** A Google project that lacks entitlement to a preview
 *     model aborts that single case ([TestAbortedException] -> reported as skipped) instead of
 *     failing the whole suite. A genuine crash (bad request, serialization error, NPE, ...) still
 *     fails loudly.
 *
 * Requires `GEMINI_API_KEY` (or `GOOGLE_API_KEY`) to be set; otherwise the whole class is disabled.
 */
@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gemini-2.5-flash",
        "embabel.agent.platform.models.googlegenai.max-attempts=1",
        "embabel.agent.platform.models.googlegenai.api-key=\${GOOGLE_API_KEY:\${GEMINI_API_KEY:dummy-key-for-context-loading}}",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("google-genai-chat-test")
@Import(
    GoogleGenAiChatTestConfig::class,
    AgentGoogleGenAiAutoConfiguration::class,
)
@EnabledIfEnvironmentVariable(
    named = "GEMINI_API_KEY",
    matches = ".+",
    disabledReason = "Live Google GenAI integration test requires GEMINI_API_KEY (or GOOGLE_API_KEY)",
)
class GoogleGenAiAllModelsIT(
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
        val registered = registeredGoogleModelIds().toSet()
        val missing = EXPECTED_CHAT_MODELS.filterNot { it in registered }
        assertTrue(
            missing.isEmpty(),
            "Expected Google GenAI chat models to be registered but these are missing: $missing " +
                "(registered: ${registered.sorted()})",
        )
    }

    /**
     * One live test per registered Google GenAI chat model. Fails only if a model genuinely
     * misbehaves; skips (aborts) when the project lacks access to that model.
     */
    @TestFactory
    fun everyRegisteredModelRepliesWithoutCrashing(): Stream<DynamicTest> {
        val modelIds = registeredGoogleModelIds()
        assertTrue(
            modelIds.isNotEmpty(),
            "Expected at least one Google GenAI model to be registered, found none",
        )

        return modelIds.stream().map { modelId ->
            DynamicTest.dynamicTest("gemini model '$modelId' replies without crashing") {
                val response = try {
                    ai.withLlm(modelId).generateText(SMOKE_PROMPT).trim()
                } catch (ex: Exception) {
                    if (isModelAccessError(ex)) {
                        throw TestAbortedException(
                            "API key is set, but the configured Google project does not have access to $modelId",
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

    /** All Google GenAI chat models currently registered as [LlmService] beans, sorted for stable ordering. */
    private fun registeredGoogleModelIds(): List<String> =
        llms.asSequence()
            .filter { it.provider == GoogleGenAiModels.PROVIDER }
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
         * [GoogleGenAiModels]. Embedding models are intentionally excluded (they are not chat LLMs).
         */
        private val EXPECTED_CHAT_MODELS = listOf(
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
