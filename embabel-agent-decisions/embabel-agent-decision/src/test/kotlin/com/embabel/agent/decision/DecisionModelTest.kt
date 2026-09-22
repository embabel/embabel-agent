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
package com.embabel.agent.decision

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Duration

class DecisionModelTest {
    @Test
    fun `validates distributions and preserves ties in declaration order`() {
        val request = DecisionRequest.builder()
        val choice = request.choice("relation", "How do these relate?", listOf(
            DecisionOption.of("same", "IDENTICAL", "identical secret-label"),
            DecisionOption.of("near", "SIMILAR", "similar"),
        ))
        request.correlationId("revision:42")
        val model = DecisionModel(DecisionProvider {
            RawDecisionOutcome.success(listOf(
                RawAnswer.distribution("relation", DecisionKind.CHOICE, listOf(
                    RawProbability.of("same", .5), RawProbability.of("near", .5),
                ), "same"),
            ), DecisionProvenance.builder("stub", EvidenceKind.DISTRIBUTION).build())
        })

        val result = model.ask(request.build()) as DecisionOutcome.Success
        val answer = result.answer(choice) as KeyOutcome.Success

        assertThat(answer.value).isEqualTo("same")
        assertThat(answer.maximizers).containsExactly("same", "near")
        assertThat(answer.firstMaximizer).isEqualTo("same")
        assertThat(result.provenance.correlationId).isEqualTo("revision:42")
        assertThat(result.provenance.questionFingerprint).isNotBlank()
        assertThat(result.record!!.fields.values.joinToString()).doesNotContain("secret-label")
    }

    @Test
    fun `keeps valid sibling when a known response is malformed`() {
        val builder = DecisionRequest.builder()
        val valid = builder.yesNo("keep", "keep?")
        val invalid = builder.choice("bad", "bad?", listOf(DecisionOption.of("a", "A", "a")))
        val result = DecisionModel(DecisionProvider {
            RawDecisionOutcome.success(listOf(
                RawAnswer.yesNo("keep", 1.0, "true"),
                RawAnswer.distribution("bad", DecisionKind.CHOICE, listOf(RawProbability.of("a", .5)), "a"),
            ), DecisionProvenance.builder("test", EvidenceKind.DISTRIBUTION).build())
        }).ask(builder.build()) as DecisionOutcome.Success

        assertThat(result.answer(valid)).isInstanceOf(KeyOutcome.Success::class.java)
        assertThat(result.answer(invalid)).isInstanceOf(KeyOutcome.Failure::class.java)
    }

    @Test
    fun `rejects unknown response ids and invalid full policy`() {
        val builder = DecisionRequest.builder()
        builder.yesNo("known", "known?")
        val outcome = DecisionModel(DecisionProvider {
            RawDecisionOutcome.success(listOf(RawAnswer.yesNo("unknown", 1.0, "true")),
                DecisionProvenance.builder("test", EvidenceKind.DISTRIBUTION).build())
        }).ask(builder.build())

        assertThat(outcome).isInstanceOf(DecisionOutcome.Failure::class.java)
        assertThat((outcome as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.RejectedRequest)
        assertThatThrownBy { DecisionRecordPolicy.full(0, setOf("answerIds")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DecisionRecordPolicy.full(10, setOf("*")) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `uses request policy over defaults and does not leak redacted state`() {
        val seen = mutableMapOf<String, Any?>()
        val model = DecisionModel(DecisionProvider { prepared ->
            seen.putAll(prepared.state)
            RawDecisionOutcome.success(listOf(RawAnswer.yesNo("safe", .7, "true")),
                DecisionProvenance.builder("test", EvidenceKind.DISTRIBUTION).build())
        }).withDefaults(Duration.ofMillis(200), DecisionRecordPolicy.none())
        val builder = DecisionRequest.builder().state(mapOf("token" to "do-not-send", "visible" to "yes"))
        builder.recordPolicy(DecisionRecordPolicy.metadata())
        builder.yesNo("safe", "safe?")

        val outcome = model.ask(builder.build()) as DecisionOutcome.Success
        assertThat(seen).containsEntry("visible", "yes").doesNotContainKey("token")
        assertThat(outcome.record).isNotNull
    }

    @Test
    fun `no and stub factories use the same facade`() {
        val disabled = NoDecisionModel.create().ask(yesNoRequest()) as DecisionOutcome.Failure
        assertThat(disabled.safeCode).isEqualTo(DecisionSafeCode.DISABLED)
        val raw = RawDecisionOutcome.success(listOf(RawAnswer.yesNo("yes", .4, "false")),
            DecisionProvenance.builder("stub", EvidenceKind.DISTRIBUTION).build())
        val scripted = StubDecisionModel.create(listOf(StubStep.immediate(raw))).ask(yesNoRequest()) as DecisionOutcome.Success
        assertThat(scripted).isNotNull
    }

    private fun yesNoRequest(): DecisionRequest {
        val builder = DecisionRequest.builder()
        builder.yesNo("yes", "yes?")
        return builder.build()
    }
}
