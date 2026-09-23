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

import com.embabel.agent.core.Usage
import com.embabel.agent.decision.ChoiceKey
import com.embabel.agent.decision.DecisionOption
import com.embabel.agent.decision.DecisionOutcome
import com.embabel.agent.decision.KeyFailure
import com.embabel.agent.decision.DecisionRequest
import com.embabel.agent.decision.KeyOutcome
import com.embabel.agent.decision.RatingKey
import com.embabel.agent.decision.YesNoKey
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.loop.LlmMessageRequest
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.agent.spi.loop.RequestAwareLlmMessageSender
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.prompt.PromptContributor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.junit.jupiter.params.provider.MethodSource
import java.time.LocalDate
import java.util.stream.Stream
import tools.jackson.databind.json.JsonMapper

class PromptedDecisionModelTest {
    @ParameterizedTest
    @MethodSource("senders")
    fun `both sender paths return verbalized typed evidence`(requestAware: Boolean) {
        val sender = RecordingSender(completeResponse())
        val service = RecordingService(if (requestAware) sender.requestAware() else sender)
        val result = PromptedDecisionModel.create(service, LlmOptions()).ask(request()) as DecisionOutcome.Success

        assertThat(result.provenance.evidenceKind.name).isEqualTo("VERBALIZED")
        assertThat(result.provenance.requestedModel).isEqualTo("test-model")
        assertThat(result.provenance.resolvedModel).isNull()
        assertThat((result.answer(yes) as KeyOutcome.Success).value).isTrue()
        assertThat((result.answer(choice) as KeyOutcome.Success).value).isEqualTo("b")
        assertThat((result.answer(rating) as KeyOutcome.Success).value).isEqualTo("high")
        assertThat(sender.tools).isEmpty()
        assertThat(sender.messages.joinToString("\n") { it.content }).contains("BEGIN_DECISION_DATA")
        if (requestAware) {
            assertThat(sender.nativeRequest?.structuredOutputRequest?.name).isEqualTo("decision_response")
            assertThat(sender.nativeRequest?.structuredOutputRequest?.strict).isTrue()
            val schema = JsonMapper.builder().build().readTree(sender.nativeRequest!!.structuredOutputRequest.schema)
            assertThat(schema.get("additionalProperties").booleanValue()).isFalse()
            assertThat(schema.get("required")).extracting<String> { it.textValue() }.containsExactly("answers")
            val alternatives = schema.get("properties").get("answers").get("items").get("oneOf")
            assertThat(alternatives).hasSize(4)
            assertThat(alternatives).allSatisfy { entry ->
                assertThat(entry.get("additionalProperties").booleanValue()).isFalse()
                assertThat(entry.get("required")).isNotEmpty()
            }
        } else {
            assertThat(sender.messages.first().content).contains("JSON format")
        }
    }

    @ParameterizedTest
    @MethodSource("senders")
    fun `ties retain declaration order`(requestAware: Boolean) {
        val sender = RecordingSender(tieResponse())
        val service = RecordingService(if (requestAware) sender.requestAware() else sender)
        val result = PromptedDecisionModel.create(service, LlmOptions()).ask(request()) as DecisionOutcome.Success

        assertThat((result.answer(choice) as KeyOutcome.Success).maximizers).containsExactly("a", "b")
        assertThat((result.answer(choice) as KeyOutcome.Success).firstMaximizer).isEqualTo("a")
        assertThat((result.answer(rating) as KeyOutcome.Success).maximizers).containsExactly("low", "high")
    }

    @org.junit.jupiter.api.Test
    fun `recognizable invalid evidence preserves valid siblings and missing entries`() {
        val sender = RecordingSender("""{"answers":[{"keyId":"yes","kind":"YES_NO","pTrue":"0.75"},{"keyId":"choice","kind":"CHOICE","probabilities":[{"supportId":"a","probability":0.4},{"supportId":"b","probability":0.6}]}]}""")
        val result = PromptedDecisionModel.create(RecordingService(sender), LlmOptions()).ask(request()) as DecisionOutcome.Success

        assertThat((result.answer(yes) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Invalid)
        assertThat((result.answer(choice) as KeyOutcome.Success).value).isEqualTo("b")
        assertThat((result.answer(rating) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Missing)
    }

    @org.junit.jupiter.api.Test
    fun `unknown or duplicate answer ids reject the whole call`() {
        val sender = RecordingSender("""{"answers":[{"keyId":"yes","kind":"YES_NO","pTrue":0.5},{"keyId":"yes","kind":"YES_NO","pTrue":0.5}]}""")
        val result = PromptedDecisionModel.create(RecordingService(sender), LlmOptions()).ask(request()) as DecisionOutcome.Failure

        assertThat(result.safeCode.name).isEqualTo("REJECTED_REQUEST")
    }

    @ParameterizedTest
    @ValueSource(strings = [
        """{"keyId":"choice","kind":"CHOICE","probabilities":[{"supportId":"a","probability":1.0}]}""",
        """{"keyId":"choice","kind":"CHOICE","probabilities":[{"supportId":"a","probability":0.5},{"supportId":"b","probability":0.5}],"selectedSupportId":"a","extra":true}""",
        """{"keyId":"choice","kind":"CHOICE","probabilities":[{"supportId":"a","probability":0.2},{"supportId":"b","probability":0.8}],"selectedSupportId":"a"}""",
        """{"keyId":"choice","kind":"RATING","probabilities":[{"supportId":"a","probability":0.5},{"supportId":"b","probability":0.5}]}""",
    ])
    fun `recognizable malformed distributions become invalid key evidence`(entry: String) {
        val sender = RecordingSender("""{"answers":[$entry]}""")
        val result = PromptedDecisionModel.create(RecordingService(sender), LlmOptions()).ask(request()) as DecisionOutcome.Success

        assertThat(result.answer(choice)).isInstanceOf(KeyOutcome.Failure::class.java)
        assertThat((result.answer(choice) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Invalid)
    }

    private fun request(): DecisionRequest {
        val builder = DecisionRequest.builder()
        yes = builder.yesNo("yes", "Should this proposition be revised?")
        choice = builder.choice("choice", "Which candidate is best?", listOf(
            DecisionOption.of("a", "a", "Candidate A"),
            DecisionOption.of("b", "b", "Candidate B"),
        ))
        rating = builder.rating("rating", "How strong is the support?", listOf(
            DecisionOption.of("low", "low", "Low"),
            DecisionOption.of("medium", "medium", "Medium"),
            DecisionOption.of("high", "high", "High"),
        ))
        return builder.build()
    }

    private class RecordingService(private val sender: LlmMessageSender) : LlmService<RecordingService> {
        override val name = "test-model"
        override val provider = "test-provider"
        override val knowledgeCutoffDate: LocalDate? = null
        override val pricingModel: PricingModel? = null
        override val promptContributors: List<PromptContributor> = emptyList()
        override fun createMessageSender(options: LlmOptions): LlmMessageSender = sender
        override fun createMessageStreamer(options: LlmOptions) = error("streaming is not used")
        override fun supportsStreaming() = false
        override fun withKnowledgeCutoffDate(date: LocalDate) = this
        override fun withPromptContributor(promptContributor: PromptContributor) = this
    }

    private class RecordingSender(private val text: String) : LlmMessageSender {
        var messages: List<Message> = emptyList()
        var tools = emptyList<com.embabel.agent.api.tool.Tool>()
        var nativeRequest: com.embabel.agent.spi.loop.NativeStructuredOutputRequest? = null

        fun requestAware(): RequestAwareLlmMessageSender = object : RequestAwareLlmMessageSender {
            override fun call(request: LlmMessageRequest): LlmMessageResponse {
                messages = request.messages
                tools = request.tools
                nativeRequest = request.nativeStructuredOutputRequest
                return response()
            }
            override fun call(messages: List<Message>, tools: List<com.embabel.agent.api.tool.Tool>): LlmMessageResponse = response()
        }

        override fun call(messages: List<Message>, tools: List<com.embabel.agent.api.tool.Tool>): LlmMessageResponse {
            this.messages = messages
            this.tools = tools
            return response()
        }

        private fun response() = LlmMessageResponse(AssistantMessage(text), text, Usage(1, 1, null))
    }

    companion object {
        private lateinit var yes: YesNoKey
        private lateinit var choice: ChoiceKey<String>
        private lateinit var rating: RatingKey<String>

        @JvmStatic fun senders(): Stream<Boolean> = Stream.of(false, true)
        private fun completeResponse() = """{"answers":[{"keyId":"yes","kind":"YES_NO","pTrue":0.75},{"keyId":"choice","kind":"CHOICE","probabilities":[{"supportId":"a","probability":0.4},{"supportId":"b","probability":0.6}],"selectedSupportId":"b"},{"keyId":"rating","kind":"RATING","probabilities":[{"supportId":"low","probability":0.2},{"supportId":"medium","probability":0.3},{"supportId":"high","probability":0.5}],"selectedSupportId":"high"}]}"""
        private fun tieResponse() = """{"answers":[{"keyId":"yes","kind":"YES_NO","pTrue":0.5},{"keyId":"choice","kind":"CHOICE","probabilities":[{"supportId":"a","probability":0.5},{"supportId":"b","probability":0.5}]},{"keyId":"rating","kind":"RATING","probabilities":[{"supportId":"low","probability":0.5},{"supportId":"medium","probability":0.0},{"supportId":"high","probability":0.5}]}]}"""
    }
}
