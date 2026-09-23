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
package example.decision.provider

// tag::custom-provider[]
import com.embabel.agent.decision.CallFailure
import com.embabel.agent.decision.DecisionKind
import com.embabel.agent.decision.DecisionModel
import com.embabel.agent.decision.DecisionProvenance
import com.embabel.agent.decision.DecisionProvider
import com.embabel.agent.decision.DecisionSafeCode
import com.embabel.agent.decision.EvidenceKind
import com.embabel.agent.decision.KeyFailure
import com.embabel.agent.decision.PreparedDecisionRequest
import com.embabel.agent.decision.RawAnswer
import com.embabel.agent.decision.RawDecisionOutcome

class ThresholdDecisionProvider : DecisionProvider {
    override fun invoke(request: PreparedDecisionRequest): RawDecisionOutcome {
        if (Thread.currentThread().isInterrupted) {
            return RawDecisionOutcome.failure(CallFailure.Cancelled, DecisionSafeCode.CANCELLED)
        }
        if (request.remainingNanos() == 0L) {
            return RawDecisionOutcome.failure(CallFailure.DeadlineExceeded, DecisionSafeCode.DEADLINE_EXCEEDED)
        }
        val pTrue = (request.state["score"] as? Number)?.toDouble()?.coerceIn(0.0, 1.0) ?: 0.0
        val answers = request.questions.map { question ->
            when (question.kind) {
                DecisionKind.YES_NO -> RawAnswer.yesNo(question.id, pTrue, null)
                else -> RawAnswer.failure(question.id, KeyFailure.Unsupported, DecisionSafeCode.UNSUPPORTED)
            }
        }
        val provenance = DecisionProvenance.builder("threshold", EvidenceKind.DISTRIBUTION)
            .adapterVersion("1")
            .build()
        return RawDecisionOutcome.success(answers, provenance)
    }
}

fun thresholdDecisionModel(): DecisionModel = DecisionModel(ThresholdDecisionProvider())
// end::custom-provider[]
