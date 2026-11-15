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
import com.embabel.agent.api.common.streaming.asStreaming
import com.embabel.agent.api.event.AgentProcessEvent
import com.embabel.agent.api.event.AgenticEventListener
import com.embabel.agent.api.event.LlmInvocationEvent
import com.embabel.agent.autoconfigure.models.googlegenai.AgentGoogleGenAiAutoConfiguration
import com.embabel.agent.core.LlmInvocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Thinking tokens and cost with a real Gemini model.
 *
 * Gemini reports its thinking apart from the completion, in `thoughtsTokenCount`. It is counted in the completion, as
 * OpenAI and Anthropic count it, and billed as output. Before, a call's cost left it out.
 */
@SpringBootTest(
    properties = [
        "embabel.models.default-llm=gemini-3.8-flash",
        "embabel.agent.platform.models.googlegenai.max-attempts=1",
        "embabel.agent.platform.models.googlegenai.api-key=\${GOOGLE_API_KEY:\${GEMINI_API_KEY:dummy-key-for-context-loading}}",
        "spring.main.allow-bean-definition-overriding=true",
    ]
)
@ActiveProfiles("google-genai-chat-test")
@Import(
    GoogleGenAiChatTestConfig::class,
    AgentGoogleGenAiAutoConfiguration::class,
    GoogleGenAiThinkingCostIT.InvocationListenerConfig::class,
)
@EnabledIfEnvironmentVariable(
    named = "GEMINI_API_KEY",
    matches = ".+",
    disabledReason = "Live Google GenAI integration test requires GEMINI_API_KEY (or GOOGLE_API_KEY)",
)
class GoogleGenAiThinkingCostIT(
    @param:Autowired private val ai: Ai,
    @param:Autowired private val listener: InvocationListener,
) {

    private val question = "A bat and a ball cost 1.10 dollars in total. The bat costs 1.00 dollar more than the ball. " +
        "How many cents does the ball cost? Reply with the number only."

    private val logger = LoggerFactory.getLogger(GoogleGenAiThinkingCostIT::class.java)

    @Test
    fun `thinking is counted in the completion and billed as output`() {
        listener.reset()

        ai.withLlm(MODEL).generateText(question)

        assertThinkingBilled()
    }

    @Test
    fun `streamed thinking is counted in the completion and billed as output`() {
        listener.reset()

        val answer = ai.withLlm(MODEL).asStreaming().withPrompt(question).generateStream()
            .collectList().block(Duration.ofSeconds(120))!!.joinToString("")
        logger.info("streamed answer: '{}'", answer)

        assertThinkingBilled()
    }

    private fun assertThinkingBilled() {
        val invocations = listener.invocations()
        assertTrue(invocations.isNotEmpty(), "At least one LlmInvocationEvent must fire for the real call")
        for (invocation in invocations) {
            val usage = invocation.usage
            logger.info(
                "{} usage: prompt={}, completion={}, thinking={}, cost={}",
                MODEL, usage.promptTokens, usage.completionTokens, usage.thinkingTokens, invocation.cost(),
            )
            val pricing = invocation.llmMetadata.pricingModel!!
            assertEquals(
                pricing.costOf(usage.promptTokens!!, usage.completionTokens!!), invocation.cost(), 1e-12,
                "The cost must be the prompt and the completion at their prices",
            )
            assertTrue((usage.thinkingTokens ?: 0) <= usage.completionTokens!!, "The thinking is a part of the completion")
        }
        val thought = invocations.filter { (it.usage.thinkingTokens ?: 0) > 0 }
        assertTrue(thought.isNotEmpty(), "Gemini thinks on a reasoning question: a call must report its thinking")
        thought.forEach { invocation ->
            val usage = invocation.usage
            val answerOnly = invocation.llmMetadata.pricingModel!!
                .costOf(usage.promptTokens!!, usage.completionTokens!! - usage.thinkingTokens!!)
            assertTrue(invocation.cost() > answerOnly, "A call that thought must cost more than its answer alone")
        }
    }

    companion object {
        private const val MODEL = "gemini-3.8-flash"
    }

    /** Exposes an [InvocationListener] bean, auto-discovered by embabel. */
    @Configuration
    class InvocationListenerConfig {
        @Bean
        fun invocationListener() = InvocationListener()
    }

    /** Keeps each [LlmInvocation] a real call records. */
    class InvocationListener : AgenticEventListener {

        private val received = CopyOnWriteArrayList<LlmInvocation>()

        override fun onProcessEvent(event: AgentProcessEvent) {
            if (event is LlmInvocationEvent) received += event.invocation
        }

        fun invocations(): List<LlmInvocation> = received.toList()

        fun reset() = received.clear()
    }
}
