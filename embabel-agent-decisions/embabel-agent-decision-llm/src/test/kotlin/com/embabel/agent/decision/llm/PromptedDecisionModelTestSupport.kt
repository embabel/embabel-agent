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
package com.embabel.agent.decision.llm

import com.embabel.agent.decision.ChoiceKey
import com.embabel.agent.decision.DecisionOption
import com.embabel.agent.decision.DecisionCompletion
import com.embabel.agent.decision.DecisionInstrumentation
import com.embabel.agent.decision.DecisionObservation
import com.embabel.agent.decision.DecisionObservationContext
import com.embabel.agent.decision.DecisionRecordPolicy
import com.embabel.agent.decision.DecisionRequest
import com.embabel.agent.decision.DecisionTelemetryEvent
import com.embabel.agent.decision.RatingKey
import com.embabel.agent.decision.YesNoKey
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.loop.LlmMessageRequest
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.NativeStructuredOutputRequest
import com.embabel.agent.spi.loop.RequestAwareLlmMessageSender
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.prompt.PromptContributor
import java.time.LocalDate
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentLinkedQueue

enum class SenderPath { LEGACY, NATIVE }

internal class RecordingDecisionInstrumentation : DecisionInstrumentation {
    val events = ConcurrentLinkedQueue<DecisionTelemetryEvent>()

    override fun start(context: DecisionObservationContext): DecisionObservation = object : DecisionObservation {
        override fun <T> wrap(work: Callable<T>): Callable<T> = work
        override fun event(event: DecisionTelemetryEvent) {
            events += event
        }
        override fun complete(completion: DecisionCompletion) = Unit
        override fun close() = Unit
    }
}

internal class RecordingDecisionSender(
    private val invocation: () -> LlmMessageResponse,
) {
    var messages: List<Message> = emptyList()
    var tools = emptyList<com.embabel.agent.api.tool.Tool>()
    var nativeRequest: NativeStructuredOutputRequest? = null
    var calls: Int = 0

    fun forPath(path: SenderPath): LlmMessageSender = when (path) {
        SenderPath.LEGACY -> LlmMessageSender { messages, tools -> dispatch(messages, tools, null) }
        SenderPath.NATIVE -> object : RequestAwareLlmMessageSender {
            override fun call(request: LlmMessageRequest): LlmMessageResponse =
                dispatch(request.messages, request.tools, request.nativeStructuredOutputRequest)

            override fun call(
                messages: List<Message>,
                tools: List<com.embabel.agent.api.tool.Tool>,
            ): LlmMessageResponse = error("native sender must receive LlmMessageRequest")
        }
    }

    private fun dispatch(
        messages: List<Message>,
        tools: List<com.embabel.agent.api.tool.Tool>,
        nativeRequest: NativeStructuredOutputRequest?,
    ): LlmMessageResponse {
        calls++
        this.messages = messages
        this.tools = tools
        this.nativeRequest = nativeRequest
        return invocation()
    }

    companion object {
        fun replying(text: String) = RecordingDecisionSender {
            LlmMessageResponse(AssistantMessage(text), text)
        }

        fun throwing(failure: RuntimeException) = RecordingDecisionSender { throw failure }
    }
}

internal class TestDecisionService(
    private val sender: LlmMessageSender,
    override val provider: String = "test-provider",
    override val name: String = "test-model",
) : LlmService<TestDecisionService> {
    var callOptions: LlmOptions? = null
    override val knowledgeCutoffDate: LocalDate? = null
    override val pricingModel: PricingModel? = null
    override val promptContributors: List<PromptContributor> = emptyList()
    override fun createMessageSender(options: LlmOptions): LlmMessageSender {
        callOptions = options.copy()
        return sender
    }
    override fun createMessageStreamer(options: LlmOptions) = error("streaming is not used")
    override fun supportsStreaming() = false
    override fun withKnowledgeCutoffDate(date: LocalDate) = this
    override fun withPromptContributor(promptContributor: PromptContributor) = this
}

internal data class DecisionFixture(
    val request: DecisionRequest,
    val yes: YesNoKey,
    val choice: ChoiceKey<String>,
    val rating: RatingKey<String>,
)

internal fun decisionFixture(
    state: Map<String, Any?> = emptyMap(),
    policy: DecisionRecordPolicy = DecisionRecordPolicy.metadata(),
    correlationId: String? = null,
): DecisionFixture {
    val builder = DecisionRequest.builder().state(state).recordPolicy(policy)
    correlationId?.let(builder::correlationId)
    val yes = builder.yesNo("yes", "Should this proposition be revised?")
    val choice = builder.choice(
        "choice",
        "Which candidate is best?",
        listOf(
            DecisionOption.of("a", "a", "Candidate A"),
            DecisionOption.of("b", "b", "Candidate B"),
        ),
    )
    val rating = builder.rating(
        "rating",
        "How strong is the support?",
        listOf(
            DecisionOption.of("low", "low", "Low"),
            DecisionOption.of("medium", "medium", "Medium"),
            DecisionOption.of("high", "high", "High"),
        ),
    )
    return DecisionFixture(builder.build(), yes, choice, rating)
}

internal fun promptedFixture(name: String): String =
    requireNotNull(PromptedDecisionModelTest::class.java.getResource("/decision/prompted/$name")) {
        "missing prompted fixture $name"
    }.readText()
