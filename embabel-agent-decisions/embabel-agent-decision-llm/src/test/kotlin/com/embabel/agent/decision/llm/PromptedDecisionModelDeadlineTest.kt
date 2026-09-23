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
import com.embabel.agent.decision.DecisionRequest
import com.embabel.agent.spi.LlmService
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.agent.spi.loop.LlmMessageSender
import com.embabel.chat.AssistantMessage
import com.embabel.chat.Message
import com.embabel.common.ai.model.LlmOptions
import com.embabel.common.ai.model.PricingModel
import com.embabel.common.ai.prompt.PromptContributor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PromptedDecisionModelDeadlineTest {
    @Test
    fun `noncooperative sender is discarded at the facade deadline`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val service = BlockingService(entered, release)
        val request = DecisionRequest.builder().timeout(Duration.ofMillis(20)).also {
            it.yesNo("safe", "Is this safe?")
        }.build()

        try {
            val result = PromptedDecisionModel.create(service, LlmOptions()).ask(request)

            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue()
            assertThat(result).isInstanceOf(DecisionOutcome.Failure::class.java)
            assertThat((result as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.DeadlineExceeded)
            assertThat(service.callTimeout).isNotNull()
            assertThat(service.callTimeout!!).isLessThanOrEqualTo(Duration.ofMillis(20))
        } finally {
            release.countDown()
        }
    }

    private class BlockingService(
        private val entered: CountDownLatch,
        private val release: CountDownLatch,
    ) : LlmService<BlockingService> {
        var callTimeout: Duration? = null
        override val name = "test-model"
        override val provider = "test-provider"
        override val knowledgeCutoffDate: LocalDate? = null
        override val pricingModel: PricingModel? = null
        override val promptContributors: List<PromptContributor> = emptyList()
        override fun createMessageSender(options: LlmOptions): LlmMessageSender {
            callTimeout = options.timeout
            return LlmMessageSender { _: List<Message>, _ ->
                entered.countDown()
                release.await()
                LlmMessageResponse(AssistantMessage("{}"), "{}")
            }
        }
        override fun createMessageStreamer(options: LlmOptions) = error("streaming is not used")
        override fun supportsStreaming() = false
        override fun withKnowledgeCutoffDate(date: LocalDate) = this
        override fun withPromptContributor(promptContributor: PromptContributor) = this
    }
}
