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

import com.embabel.agent.api.models.GoogleGenAiModels
import com.embabel.agent.autoconfigure.models.googlegenai.AgentGoogleGenAiAutoConfiguration
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.ReasoningEffort
import com.google.genai.errors.ApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.opentest4j.TestAbortedException
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.google.genai.GoogleGenAiChatOptions
import org.springframework.ai.google.genai.common.GoogleGenAiThinkingLevel
import org.springframework.ai.google.genai.metadata.GoogleGenAiUsage
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.util.stream.Stream

/**
 * Checks [LlmOptions.withoutThinking] against the real API on every registered Gemini chat model.
 *
 * Where the catalog sets `thinking.can_be_disabled: true`, the model thinks by default and returns
 * no thought tokens with the opt-out. Elsewhere no `thinkingBudget` is sent and the call still works.
 * With a [ReasoningEffort] as well, the opt-out wins where thinking can be turned off: only the zero budget
 * is sent, never with a level, which Gemini would reject. Elsewhere the level is sent.
 * A model the account cannot access is skipped, as are Gemini 2.5 models where a level would be sent.
 */
abstract class GoogleGenAiThinkingOptOutTestSupport(
    private val llms: List<LlmService<*>>,
) {

    @TestFactory
    fun `withoutThinking on every model`(): Stream<DynamicTest> =
        geminiModels().map { llm -> DynamicTest.dynamicTest(llm.name) { checkOptOut(llm) } }

    @TestFactory
    fun `withoutThinking with a reasoning effort on every model`(): Stream<DynamicTest> =
        geminiModels().map { llm -> DynamicTest.dynamicTest(llm.name) { checkOptOutWithEffort(llm) } }

    private fun geminiModels(): Stream<SpringAiLlmService> {
        val models = llms.filterIsInstance<SpringAiLlmService>()
            .filter { it.provider == GoogleGenAiModels.PROVIDER }
            .sortedBy { it.name }
        assertTrue(models.isNotEmpty(), "Expected Google GenAI models to be registered")
        return models.stream()
    }

    private fun checkOptOutWithEffort(llm: SpringAiLlmService) {
        val model = llm.name
        val options = llm.optionsConverter.convertOptions(
            LlmOptions(model).withoutThinking().withReasoningEffort(ReasoningEffort.LOW), model
        ) as GoogleGenAiChatOptions

        if (options.thinkingBudget == 0) {
            assertNull(options.thinkingLevel, "Expected no thinkingLevel next to thinkingBudget 0 for $model")
            assertEquals(0, thoughtsTokenCount(llm, options), "Expected no thought tokens from $model with withoutThinking()")
        } else {
            assertEquals(GoogleGenAiThinkingLevel.LOW, options.thinkingLevel, "Expected the level to be sent for $model")
            assertNull(options.thinkingBudget, "Expected no thinkingBudget for $model, which cannot disable thinking")
            if (model.startsWith("gemini-2.5")) {
                throw TestAbortedException("$model takes no thinking level")
            }
            thoughtsTokenCount(llm, options)
        }
    }

    private fun checkOptOut(llm: SpringAiLlmService) {
        val model = llm.name
        val optOut = llm.optionsConverter.convertOptions(LlmOptions(model).withoutThinking(), model)
            as GoogleGenAiChatOptions

        if (optOut.thinkingBudget == 0) {
            val defaultThoughts = thoughtsTokenCount(llm, llm.optionsConverter.convertOptions(LlmOptions(model), model))
            assertTrue(defaultThoughts > 0, "Expected $model to think by default, got $defaultThoughts thought tokens")
            assertEquals(0, thoughtsTokenCount(llm, optOut), "Expected no thought tokens from $model with withoutThinking()")
        } else {
            assertNull(optOut.thinkingBudget, "Expected no thinkingBudget for $model, which cannot disable thinking")
            thoughtsTokenCount(llm, optOut)
        }
    }

    private fun thoughtsTokenCount(llm: SpringAiLlmService, options: ChatOptions): Int {
        val response = try {
            llm.chatModel.call(Prompt(listOf(UserMessage(QUESTION)), options))
        } catch (ex: Exception) {
            if (isModelAccessError(ex)) {
                throw TestAbortedException("No access to ${llm.name}", ex)
            }
            throw ex
        }
        return (response.metadata.usage as GoogleGenAiUsage).thoughtsTokenCount ?: 0
    }

    private fun isModelAccessError(ex: Exception): Boolean =
        generateSequence<Throwable>(ex) { it.cause }.any { cause ->
            (cause is ApiException && cause.code() in setOf(403, 404)) ||
                cause.message?.contains("no longer available", ignoreCase = true) == true
        }

    companion object {
        private const val QUESTION = "A bat and a ball cost 1.10 in total. The bat costs 1.00 more than the ball. " +
            "How much does the ball cost? Answer with the number only."
    }
}

@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gemini-3.8-flash",
        "embabel.agent.platform.models.googlegenai.max-attempts=1",
        "embabel.agent.platform.models.googlegenai.api-key=\${GOOGLE_API_KEY:\${GEMINI_API_KEY:dummy-key-for-context-loading}}",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("google-genai-chat-test")
@Import(GoogleGenAiChatTestConfig::class, AgentGoogleGenAiAutoConfiguration::class)
@EnabledIfEnvironmentVariable(named = "GEMINI_API_KEY", matches = ".+", disabledReason = "Integration test requires GEMINI_API_KEY")
class GoogleGenAiThinkingOptOutIT(
    @Autowired llms: List<LlmService<*>>,
) : GoogleGenAiThinkingOptOutTestSupport(llms)

@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gemini-3.8-flash",
        "embabel.agent.platform.models.googlegenai.max-attempts=1",
        "embabel.agent.platform.models.googlegenai.project-id=\${GOOGLE_PROJECT_ID}",
        "embabel.agent.platform.models.googlegenai.location=global",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("google-genai-chat-test")
@Import(GoogleGenAiChatTestConfig::class, AgentGoogleGenAiAutoConfiguration::class)
@EnabledIfEnvironmentVariable(
    named = "EMBABEL_RUN_VERTEX_INTEGRATION_TESTS",
    matches = ".+",
    disabledReason = "Integration test needs valid Vertex AI application default credentials",
)
@EnabledIfEnvironmentVariable(named = "GOOGLE_PROJECT_ID", matches = ".+", disabledReason = "Integration test requires GOOGLE_PROJECT_ID")
class GoogleGenAiThinkingOptOutVertexIT(
    @Autowired llms: List<LlmService<*>>,
) : GoogleGenAiThinkingOptOutTestSupport(llms)
