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

import com.embabel.agent.decision.CallFailure
import com.embabel.agent.decision.DecisionOutcome
import com.embabel.agent.decision.DecisionTelemetryEvent
import com.embabel.agent.decision.KeyFailure
import com.embabel.agent.decision.KeyOutcome
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.util.EmbabelObjectMapperHolder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.stream.Stream

class PromptedDecisionModelTest {
    @ParameterizedTest
    @EnumSource(SenderPath::class)
    fun `both sender paths return complete verbalized typed evidence from the fixture`(path: SenderPath) {
        val sender = RecordingDecisionSender.replying(promptedFixture("complete.json"))
        val service = TestDecisionService(sender.forPath(path))
        val fixture = decisionFixture(correlationId = "dice-revision-42")

        val result = PromptedDecisionModel.create(service, LlmOptions()).ask(fixture.request) as DecisionOutcome.Success

        assertThat(result.provenance.evidenceKind.name).isEqualTo("VERBALIZED")
        assertThat(result.provenance.requestedModel).isEqualTo("test-model")
        assertThat(result.provenance.resolvedModel).isNull()
        assertThat(result.provenance.correlationId).isEqualTo("dice-revision-42")
        assertThat(result.provenance.adapterVersion).isEqualTo("prompted-v1")
        assertThat(result.provenance.promptVersion).isEqualTo("prompted-v1")
        assertThat(result.provenance.questionFingerprint).isNotBlank()
        assertThat((result.answer(fixture.yes) as KeyOutcome.Success).value).isTrue()
        assertThat((result.answer(fixture.choice) as KeyOutcome.Success).value).isEqualTo("b")
        assertThat((result.answer(fixture.rating) as KeyOutcome.Success).value).isEqualTo("high")
        assertThat(sender.calls).isEqualTo(1)
        assertThat(sender.tools).isEmpty()
        assertThat(sender.messages.joinToString("\n") { it.content }).contains("BEGIN_DECISION_DATA")
        assertSchemaAndDispatch(path, sender)
    }

    @ParameterizedTest
    @EnumSource(SenderPath::class)
    fun `ties fixture retains declaration order`(path: SenderPath) {
        val sender = RecordingDecisionSender.replying(promptedFixture("ties.json"))
        val fixture = decisionFixture()

        val result = PromptedDecisionModel.create(TestDecisionService(sender.forPath(path)), LlmOptions())
            .ask(fixture.request) as DecisionOutcome.Success

        assertThat((result.answer(fixture.yes) as KeyOutcome.Success).maximizers).containsExactly(false, true)
        assertThat((result.answer(fixture.choice) as KeyOutcome.Success).maximizers).containsExactly("a", "b")
        assertThat((result.answer(fixture.choice) as KeyOutcome.Success).firstMaximizer).isEqualTo("a")
        assertThat((result.answer(fixture.rating) as KeyOutcome.Success).maximizers).containsExactly("low", "high")
    }

    @ParameterizedTest
    @EnumSource(SenderPath::class)
    fun `invalid evidence fixture preserves recognizable key and missing siblings`(path: SenderPath) {
        val sender = RecordingDecisionSender.replying(promptedFixture("invalid-evidence.json"))
        val fixture = decisionFixture()

        val result = PromptedDecisionModel.create(TestDecisionService(sender.forPath(path)), LlmOptions())
            .ask(fixture.request) as DecisionOutcome.Success

        assertThat((result.answer(fixture.yes) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Invalid)
        assertThat((result.answer(fixture.choice) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Missing)
        assertThat((result.answer(fixture.rating) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Missing)
    }

    @ParameterizedTest
    @EnumSource(SenderPath::class)
    fun `malformed fixture rejects the whole call`(path: SenderPath) {
        val sender = RecordingDecisionSender.replying(promptedFixture("malformed.json"))
        val result = PromptedDecisionModel.create(TestDecisionService(sender.forPath(path)), LlmOptions())
            .ask(decisionFixture().request) as DecisionOutcome.Failure

        assertThat(result.failure).isEqualTo(CallFailure.RejectedRequest)
    }

    @ParameterizedTest
    @EnumSource(SenderPath::class)
    fun `both sender paths emit one bounded event sequence per sender call`(path: SenderPath) {
        val success = RecordingDecisionInstrumentation()
        val successfulSender = RecordingDecisionSender.replying(promptedFixture("complete.json"))
        PromptedDecisionModel.create(TestDecisionService(successfulSender.forPath(path)), LlmOptions())
            .withInstrumentation(success)
            .ask(decisionFixture().request)
        assertThat(successfulSender.calls).isEqualTo(1)
        assertThat(success.events).containsExactly(
            DecisionTelemetryEvent.PROVIDER_ATTEMPT,
            DecisionTelemetryEvent.TRANSPORT_SUCCESS,
        )

        val rejected = RecordingDecisionInstrumentation()
        PromptedDecisionModel.create(
            TestDecisionService(RecordingDecisionSender.replying("{").forPath(path)),
            LlmOptions(),
        ).withInstrumentation(rejected).ask(decisionFixture().request)
        assertThat(rejected.events).containsExactly(
            DecisionTelemetryEvent.PROVIDER_ATTEMPT,
            DecisionTelemetryEvent.TRANSPORT_SUCCESS,
            DecisionTelemetryEvent.REJECTION,
        )

        val unavailable = RecordingDecisionInstrumentation()
        PromptedDecisionModel.create(
            TestDecisionService(RecordingDecisionSender.throwing(IllegalStateException("SECRET_DO_NOT_LOG")).forPath(path)),
            LlmOptions(),
        ).withInstrumentation(unavailable).ask(decisionFixture().request)
        assertThat(unavailable.events).containsExactly(
            DecisionTelemetryEvent.PROVIDER_ATTEMPT,
            DecisionTelemetryEvent.TRANSPORT_UNAVAILABLE,
        )
        assertThat(unavailable.events.joinToString()).doesNotContain("SECRET_DO_NOT_LOG")
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("wireCases")
    fun `strict parser and common facade classify adversarial evidence`(
        path: SenderPath,
        case: WireCase,
    ) {
        val sender = RecordingDecisionSender.replying(case.json)
        val fixture = decisionFixture()
        val outcome = PromptedDecisionModel.create(TestDecisionService(sender.forPath(path)), LlmOptions())
            .ask(fixture.request)

        when (case.expected) {
            Expected.CALL_REJECTED -> {
                assertThat(outcome).isInstanceOf(DecisionOutcome.Failure::class.java)
                assertThat((outcome as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.RejectedRequest)
            }
            Expected.CHOICE_INVALID -> assertKeyFailure(outcome, fixture, KeyFailure.Invalid)
            Expected.CHOICE_MISSING -> assertKeyFailure(outcome, fixture, KeyFailure.Missing)
            Expected.CHOICE_UNSUPPORTED -> assertKeyFailure(outcome, fixture, KeyFailure.Unsupported)
            Expected.CHOICE_SUCCESS -> {
                val answer = (outcome as DecisionOutcome.Success).answer(fixture.choice) as KeyOutcome.Success
                assertThat(answer.value).isEqualTo("b")
            }
            Expected.YES_INVALID -> {
                val answer = (outcome as DecisionOutcome.Success).answer(fixture.yes) as KeyOutcome.Failure
                assertThat(answer.failure).isEqualTo(KeyFailure.Invalid)
            }
        }
    }

    @ParameterizedTest
    @MethodSource("nonFiniteFacts")
    fun `non-finite state is rejected before either sender path is called`(path: SenderPath, value: Number) {
        val sender = RecordingDecisionSender.replying(promptedFixture("complete.json"))
        val fixture = decisionFixture(state = mapOf("measurement" to value))

        val outcome = PromptedDecisionModel.create(TestDecisionService(sender.forPath(path)), LlmOptions())
            .ask(fixture.request) as DecisionOutcome.Failure

        assertThat(outcome.failure).isEqualTo(CallFailure.RejectedRequest)
        assertThat(sender.calls).isZero()
    }

    @Test
    fun `plain ObjectMapper holder is accepted without narrowing to JsonMapper`() {
        val sender = RecordingDecisionSender.replying(promptedFixture("complete.json"))
        val fixture = decisionFixture()

        val outcome = PromptedDecisionModel.create(
            TestDecisionService(sender.forPath(SenderPath.LEGACY)),
            LlmOptions(),
            EmbabelObjectMapperHolder(ObjectMapper()),
        ).ask(fixture.request)

        assertThat(outcome).isInstanceOf(DecisionOutcome.Success::class.java)
    }

    @Test
    fun `factory validates service provenance before creating a sender`() {
        val sender = RecordingDecisionSender.replying(promptedFixture("complete.json"))
        val validBoundary = "p".repeat(256)

        assertThat(
            PromptedDecisionModel.create(
                TestDecisionService(sender.forPath(SenderPath.LEGACY), provider = validBoundary),
                LlmOptions(),
            ),
        ).isNotNull()
        listOf(
            TestDecisionService(sender.forPath(SenderPath.LEGACY), provider = ""),
            TestDecisionService(sender.forPath(SenderPath.LEGACY), provider = "unsafe\nprovider"),
            TestDecisionService(sender.forPath(SenderPath.LEGACY), provider = "p".repeat(257)),
            TestDecisionService(sender.forPath(SenderPath.LEGACY), name = "unsafe\rmodel"),
            TestDecisionService(sender.forPath(SenderPath.LEGACY), name = "m".repeat(257)),
        ).forEach { service ->
            assertThatThrownBy { PromptedDecisionModel.create(service, LlmOptions()) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThat(sender.calls).isZero()
    }

    @Test
    fun `caller mapper features cannot loosen wire parsing or mutate shared serialization`() {
        val shared = JsonMapper.builder()
            .enable(tools.jackson.core.json.JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(tools.jackson.core.json.JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .build()
        val holder = EmbabelObjectMapperHolder(shared)
        val malformed = RecordingDecisionSender.replying("""{"answers":[],}""")

        val result = PromptedDecisionModel.create(
            TestDecisionService(malformed.forPath(SenderPath.LEGACY)),
            LlmOptions(),
            holder,
        ).ask(decisionFixture().request) as DecisionOutcome.Failure

        assertThat(result.failure).isEqualTo(CallFailure.RejectedRequest)
        assertThat(shared.writeValueAsString("a_b")).isEqualTo("\"a_b\"")
    }

    @Test
    fun `factory snapshots mutable caller options and applies remaining budget to a fresh copy`() {
        val sender = RecordingDecisionSender.replying(promptedFixture("complete.json"))
        val service = TestDecisionService(sender.forPath(SenderPath.LEGACY))
        val callerOptions = LlmOptions(model = "original", temperature = 0.25, timeout = Duration.ofMinutes(1))
        val model = PromptedDecisionModel.create(service, callerOptions)
        callerOptions.model = "mutated"
        callerOptions.temperature = 0.9

        val outcome = model.ask(decisionFixture().request)

        assertThat(outcome).isInstanceOf(DecisionOutcome.Success::class.java)
        assertThat(service.callOptions?.model).isEqualTo("original")
        assertThat(service.callOptions?.temperature).isEqualTo(0.25)
        assertThat(service.callOptions?.timeout).isLessThan(Duration.ofMinutes(1))
        assertThat(callerOptions.timeout).isEqualTo(Duration.ofMinutes(1))
    }

    private fun assertSchemaAndDispatch(path: SenderPath, sender: RecordingDecisionSender) {
        if (path == SenderPath.LEGACY) {
            assertThat(sender.nativeRequest).isNull()
            assertThat(sender.messages.first().content).contains("JSON format", "additionalProperties")
            return
        }
        val structured = requireNotNull(sender.nativeRequest).structuredOutputRequest
        assertThat(structured.name).isEqualTo("decision_response")
        assertThat(structured.strict).isTrue()
        val schema = JsonMapper.builder().build().readTree(structured.schema)
        assertThat(schema.get("additionalProperties").booleanValue()).isFalse()
        assertThat(schema.get("required")).extracting<String> { it.asString() }.containsExactly("answers")
        val alternatives = schema.get("properties").get("answers").get("items").get("oneOf")
        assertThat(alternatives).hasSize(4)
        assertThat(alternatives).allSatisfy { entry ->
            assertThat(entry.get("additionalProperties").booleanValue()).isFalse()
            assertThat(entry.get("required")).isNotEmpty()
        }
    }

    private fun assertKeyFailure(outcome: DecisionOutcome, fixture: DecisionFixture, failure: KeyFailure) {
        assertThat(outcome).isInstanceOf(DecisionOutcome.Success::class.java)
        val answer = (outcome as DecisionOutcome.Success).answer(fixture.choice) as KeyOutcome.Failure
        assertThat(answer.failure).isEqualTo(failure)
        assertThat((outcome.answer(fixture.yes) as KeyOutcome.Failure).failure).isEqualTo(KeyFailure.Missing)
    }

    enum class Expected { CALL_REJECTED, CHOICE_INVALID, CHOICE_MISSING, CHOICE_UNSUPPORTED, CHOICE_SUCCESS, YES_INVALID }
    data class WireCase(val name: String, val json: String, val expected: Expected) {
        override fun toString() = name
    }

    companion object {
        @JvmStatic
        fun wireCases(): Stream<Arguments> {
            val cases = listOf(
                WireCase("absent answer", """{"answers":[]}""", Expected.CHOICE_MISSING),
                WireCase("explicit unsupported", """{"answers":[{"keyId":"choice","kind":"UNSUPPORTED"}]}""", Expected.CHOICE_UNSUPPORTED),
                WireCase("duplicate answer", """{"answers":[{"keyId":"choice","kind":"UNSUPPORTED"},{"keyId":"choice","kind":"UNSUPPORTED"}]}""", Expected.CALL_REJECTED),
                WireCase("unknown answer", """{"answers":[{"keyId":"unknown","kind":"UNSUPPORTED"}]}""", Expected.CALL_REJECTED),
                WireCase("duplicate support", distribution("""{"supportId":"a","probability":0.5},{"supportId":"a","probability":0.5}"""), Expected.CHOICE_INVALID),
                WireCase("incomplete support", distribution("""{"supportId":"a","probability":1.0}"""), Expected.CHOICE_INVALID),
                WireCase("extra support", distribution("""{"supportId":"a","probability":0.3},{"supportId":"b","probability":0.3},{"supportId":"c","probability":0.4}"""), Expected.CHOICE_INVALID),
                WireCase("numeric string", distribution("""{"supportId":"a","probability":"0.4"},{"supportId":"b","probability":0.6}"""), Expected.CHOICE_INVALID),
                WireCase("NaN token", """{"answers":[{"keyId":"yes","kind":"YES_NO","pTrue":NaN}]}""", Expected.CALL_REJECTED),
                WireCase("Infinity token", """{"answers":[{"keyId":"yes","kind":"YES_NO","pTrue":Infinity}]}""", Expected.CALL_REJECTED),
                WireCase("overflow", """{"answers":[{"keyId":"yes","kind":"YES_NO","pTrue":1e999}]}""", Expected.YES_INVALID),
                WireCase("negative", distribution("""{"supportId":"a","probability":-0.1},{"supportId":"b","probability":1.1}"""), Expected.CHOICE_INVALID),
                WireCase("greater than one", distribution("""{"supportId":"a","probability":1.1},{"supportId":"b","probability":-0.1}"""), Expected.CHOICE_INVALID),
                WireCase("mass outside tolerance", distribution("""{"supportId":"a","probability":0.5},{"supportId":"b","probability":0.500000002}"""), Expected.CHOICE_INVALID),
                WireCase("mass inside tolerance", distribution("""{"supportId":"a","probability":0.5},{"supportId":"b","probability":0.5000000005}"""), Expected.CHOICE_SUCCESS),
                WireCase("nonmaximizing selection", distribution("""{"supportId":"a","probability":0.2},{"supportId":"b","probability":0.8}""", ",\"selectedSupportId\":\"a\""), Expected.CHOICE_INVALID),
                WireCase("wrong kind", """{"answers":[{"keyId":"choice","kind":"RATING","probabilities":[{"supportId":"a","probability":0.5},{"supportId":"b","probability":0.5}]}]}""", Expected.CHOICE_INVALID),
                WireCase("null evidence", """{"answers":[{"keyId":"yes","kind":"YES_NO","pTrue":null}]}""", Expected.YES_INVALID),
                WireCase("duplicate member", """{"answers":[{"keyId":"yes","keyId":"yes","kind":"YES_NO","pTrue":0.5}]}""", Expected.CALL_REJECTED),
                WireCase("trailing document", """{"answers":[]} {"answers":[]}""", Expected.CALL_REJECTED),
                WireCase("invalid envelope", """{"answers":[],"provider":"model-authored"}""", Expected.CALL_REJECTED),
            )
            return SenderPath.entries.flatMap { path -> cases.map { Arguments.of(path, it) } }.stream()
        }

        @JvmStatic
        fun nonFiniteFacts(): Stream<Arguments> = SenderPath.entries.flatMap { path ->
            listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Float.NaN, Float.POSITIVE_INFINITY)
                .map { Arguments.of(path, it) }
        }.stream()

        private fun distribution(probabilities: String, suffix: String = "") =
            """{"answers":[{"keyId":"choice","kind":"CHOICE","probabilities":[$probabilities]$suffix}]}"""
    }
}
