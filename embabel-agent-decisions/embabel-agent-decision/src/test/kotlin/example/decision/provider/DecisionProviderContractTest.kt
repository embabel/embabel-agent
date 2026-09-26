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

import com.embabel.agent.decision.api.DecisionOutcome
import com.embabel.agent.decision.api.DecisionRecord
import com.embabel.agent.decision.api.DecisionRecordPolicy
import com.embabel.agent.decision.api.DecisionRequest
import com.embabel.agent.decision.api.KeyOutcome
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DecisionProviderContractTest {

    @Test
    fun `external provider uses public SPI only`() {
        val builder = DecisionRequest.builder()
            .state(mapOf("score" to 0.8))
            .recordPolicy(DecisionRecordPolicy.none())
        val accepted = builder.yesNo("accepted", "Does this clear the threshold?")

        val outcome = thresholdDecisionModel().ask(builder.build())

        assertThat(describe(outcome)).isEqualTo("success")
        val success = outcome as DecisionOutcome.Success
        val answer = success.answer(accepted) as KeyOutcome.Success
        val nullableRecord: DecisionRecord? = success.record
        val nullableExpectedScore: Double? = answer.expectedScore
        assertThat(answer.value).isTrue()
        assertThat(nullableRecord).isNull()
        assertThat(nullableExpectedScore).isNull()
    }

    @Test
    fun `outcome families are closed to the two public variants`() {
        assertThat(DecisionOutcome::class.java.isSealed).isTrue()
        assertThat(DecisionOutcome::class.java.permittedSubclasses.toList())
            .containsExactlyInAnyOrder(DecisionOutcome.Success::class.java, DecisionOutcome.Failure::class.java)
        assertThat(KeyOutcome::class.java.isSealed).isTrue()
        assertThat(KeyOutcome::class.java.permittedSubclasses.toList())
            .containsExactlyInAnyOrder(KeyOutcome.Success::class.java, KeyOutcome.Failure::class.java)
    }

    private fun describe(outcome: DecisionOutcome): String = when (outcome) {
        is DecisionOutcome.Success -> "success"
        is DecisionOutcome.Failure -> "failure:${outcome.safeCode}"
    }

    private fun describe(outcome: KeyOutcome<*>): String = when (outcome) {
        is KeyOutcome.Success -> "success"
        is KeyOutcome.Failure -> "failure:${outcome.safeCode}"
    }
}
