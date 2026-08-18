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
package com.embabel.agent.test.loop

import com.embabel.agent.api.tool.Tool
import com.embabel.agent.api.tool.ToolCallContext
import com.embabel.agent.api.tool.config.ToolLoopConfiguration
import com.embabel.agent.spi.loop.AutoCorrectionPolicy
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.RetryWithFeedbackPolicy
import com.embabel.agent.spi.loop.ToolInjectionStrategy
import com.embabel.agent.spi.loop.ToolLoopFactory
import com.embabel.agent.spi.support.ExecutorAsyncer
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.chat.AssistantMessageWithToolCalls
import com.embabel.chat.Message
import com.embabel.chat.UserMessage
import com.embabel.common.ai.model.LlmOptions
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.util.concurrent.Executor

/**
 * Live check that the re-prompt after a blank turn reaches the model, run against a real provider.
 *
 * The blank turn — no text, no tool call — is in the history before the loop knows it is blank.
 * [RetryWithFeedbackPolicy] then adds a nudge and sends the whole history again. Before the fix
 * the blank turn went with it, and the providers that reject it answered 400:
 *
 * ```
 * Vertex:  Unable to submit request because it must include at least one parts field,
 *          which describes the prompt input.
 * Mistral: Assistant message must have either content or tool_calls, but not none.
 * ```
 *
 * OpenAI and Anthropic take the same history, which is why the loop cannot leave this to the
 * provider. Their subclasses do not fail before the fix; they tell us if those providers ever
 * stop taking it.
 *
 * Each provider module subclasses this and supplies its own autoconfiguration through
 * [withContext]. The first turn is forced blank, the second call is real.
 */
abstract class BlankTurnRePromptTestSupport(
    private val modelName: String,
) {

    /** Applies the module's autoconfiguration and hands [check] the resulting context. */
    protected abstract fun withContext(check: (ApplicationContext) -> Unit)

    @Test
    fun `the re-prompt after a blank turn reaches the model`() {
        withContext { context ->
            val llm = context.getBeansOfType(SpringAiLlmService::class.java).values
                .firstOrNull { it.name == modelName }
            assertNotNull(llm, "no LLM service registered for $modelName")

            val sender = BlankFirstTurn(llm!!.createMessageSender(LlmOptions(modelName)))
            val result = toolLoop(sender).execute(
                initialMessages = listOf(UserMessage(QUESTION)),
                initialTools = emptyList(),
                outputParser = { it },
            )

            assertEquals(EXPECTED_CALLS, sender.calls, "the policy re-prompts once after the blank turn")
            assertTrue(
                result.result.contains(EXPECTED_ANSWER),
                "the model answered the re-prompt: ${result.result}",
            )
        }
    }

    private fun toolLoop(sender: LlmMessageSender) =
        ToolLoopFactory.create(
            config = ToolLoopConfiguration(),
            asyncer = ExecutorAsyncer(Executor { it.run() }),
            defaultToolNotFoundPolicy = AutoCorrectionPolicy(),
        ).create(
            llmMessageSender = sender,
            objectMapper = jacksonObjectMapper(),
            injectionStrategy = ToolInjectionStrategy.NONE,
            maxIterations = MAX_ITERATIONS,
            toolDecorator = null,
            toolLoopInspectors = emptyList(),
            toolLoopTransformers = emptyList(),
            toolCallInspectors = emptyList(),
            toolCallContext = ToolCallContext.EMPTY,
            emptyResponsePolicy = RetryWithFeedbackPolicy(maxRetries = 1),
        )

    /**
     * The real sender with its first turn forced blank. Later calls are real and carry whatever
     * history the loop kept.
     */
    private class BlankFirstTurn(private val delegate: LlmMessageSender) : LlmMessageSender {

        var calls = 0
            private set

        override fun call(messages: List<Message>, tools: List<Tool>): LlmMessageResponse {
            calls++
            return if (calls == 1) {
                LlmMessageResponse(
                    message = AssistantMessageWithToolCalls(content = "", toolCalls = emptyList()),
                    textContent = "",
                )
            } else {
                delegate.call(messages, tools)
            }
        }
    }

    private companion object {
        const val QUESTION = "What is 2 + 2? Reply with the number only."

        /** The answer to [QUESTION], which only a model that saw the nudge can give. */
        const val EXPECTED_ANSWER = "4"

        /** The forced blank turn, plus the single re-prompt `maxRetries = 1` allows. */
        const val EXPECTED_CALLS = 2

        /** Room for the re-prompt without letting a misbehaving model loop for long. */
        const val MAX_ITERATIONS = 4
    }
}
