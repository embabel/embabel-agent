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

import com.embabel.agent.decision.api.llm.PromptedDecisionModel
import com.embabel.agent.decision.api.CallFailure
import com.embabel.agent.decision.api.DecisionKind
import com.embabel.agent.decision.api.DecisionModel
import com.embabel.agent.decision.api.DecisionOutcome
import com.embabel.agent.decision.api.DecisionProvider
import com.embabel.agent.decision.api.DecisionRecordPolicy
import com.embabel.agent.decision.api.DecisionTelemetryEvent
import com.embabel.agent.decision.api.PreparedDecisionRequest
import com.embabel.agent.decision.api.PreparedQuestion
import com.embabel.agent.decision.api.PreparedSupport
import com.embabel.agent.spi.loop.LlmMessageResponse
import com.embabel.chat.AssistantMessage
import com.embabel.common.ai.model.LlmOptions
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class PromptedDecisionModelDeadlineTest {
    @Test
    fun `provider propagates wrapped interruption without converting it to unavailability`() {
        val wrapped = RuntimeException(IOException("request interrupted", InterruptedException()))
        val sender = RecordingDecisionSender.throwing(wrapped)
        val provider = providerFrom(
            PromptedDecisionModel.create(TestDecisionService(sender.forPath(SenderPath.LEGACY)), LlmOptions()),
        )

        assertThatThrownBy { provider.invoke(MutablePreparedRequest(AtomicLong(Duration.ofSeconds(2).toNanos()))) }
            .isInstanceOf(InterruptedException::class.java)
        assertThat(Thread.currentThread().isInterrupted).isFalse()
    }

    @Test
    fun `wrapped cancellation emits an attempt and facade cancellation without transport failure`() {
        val instrumentation = RecordingDecisionInstrumentation()
        val wrapped = RuntimeException(IOException("request interrupted", InterruptedException()))
        val sender = RecordingDecisionSender.throwing(wrapped)
        val outcome = PromptedDecisionModel.create(
            TestDecisionService(sender.forPath(SenderPath.LEGACY)),
            LlmOptions(),
        ).withInstrumentation(instrumentation).ask(decisionFixture().request)

        assertThat(outcome).isInstanceOf(DecisionOutcome.Failure::class.java)
        assertThat((outcome as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.Cancelled)
        assertThat(instrumentation.events).containsExactly(
            DecisionTelemetryEvent.PROVIDER_ATTEMPT,
            DecisionTelemetryEvent.CANCELLATION,
        )
        assertThat(Thread.currentThread().isInterrupted).isFalse()
    }

    @Test
    fun `provider propagates an existing worker interruption without clearing or restoring it`() {
        val sender = RecordingDecisionSender.throwing(IllegalStateException("synthetic failure"))
        val provider = providerFrom(
            PromptedDecisionModel.create(TestDecisionService(sender.forPath(SenderPath.NATIVE)), LlmOptions()),
        )

        Thread.currentThread().interrupt()
        try {
            assertThatThrownBy { provider.invoke(MutablePreparedRequest(AtomicLong(Duration.ofSeconds(2).toNanos()))) }
                .isInstanceOf(InterruptedException::class.java)
            assertThat(Thread.currentThread().isInterrupted).isTrue()
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `socket timeout remains transport unavailability rather than cancellation`() {
        val sender = RecordingDecisionSender.throwing(RuntimeException(SocketTimeoutException("synthetic timeout")))
        val provider = providerFrom(
            PromptedDecisionModel.create(TestDecisionService(sender.forPath(SenderPath.LEGACY)), LlmOptions()),
        )

        val raw = provider.invoke(MutablePreparedRequest(AtomicLong(Duration.ofSeconds(2).toNanos())))

        assertThat(raw.callFailure).isEqualTo(CallFailure.Unavailable)
        assertThat(Thread.currentThread().isInterrupted).isFalse()
    }

    @Test
    fun `provider consumes one deterministic remaining budget and discards late response`() {
        val remaining = AtomicLong(Duration.ofSeconds(2).toNanos())
        val sender = RecordingDecisionSender {
            remaining.set(0)
            LlmMessageResponse(AssistantMessage(promptedFixture("complete.json")), promptedFixture("complete.json"))
        }
        val service = TestDecisionService(sender.forPath(SenderPath.NATIVE))
        val provider = providerFrom(PromptedDecisionModel.create(service, LlmOptions()))

        val raw = provider.invoke(MutablePreparedRequest(remaining))

        assertThat(raw.callFailure).isEqualTo(CallFailure.DeadlineExceeded)
        assertThat(service.callOptions?.timeout).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(2))
        assertThat(sender.calls).isEqualTo(1)
    }

    @Test
    fun `rejects an oversized prompted request before sender dispatch`() {
        val sender = RecordingDecisionSender.replying(promptedFixture("complete.json"))
        val provider = providerFrom(PromptedDecisionModel.create(TestDecisionService(sender.forPath(SenderPath.NATIVE)), LlmOptions()))
        val normal = MutablePreparedRequest(AtomicLong(Duration.ofSeconds(2).toNanos()))
        val events = ConcurrentLinkedQueue<DecisionTelemetryEvent>()
        val oversized = object : PreparedDecisionRequest by normal {
            override val state = mapOf("callerProjection" to "x".repeat(1_048_577))
            override fun event(event: DecisionTelemetryEvent) {
                events += event
            }
        }

        val raw = provider.invoke(oversized)

        assertThat(raw.callFailure).isEqualTo(CallFailure.RejectedRequest)
        assertThat(sender.calls).isZero()
        assertThat(events).containsExactly(DecisionTelemetryEvent.REJECTION)
    }

    @Test
    fun `facade deadline bounds a sender that has definitely entered`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sender = RecordingDecisionSender {
            entered.countDown()
            release.await()
            LlmMessageResponse(AssistantMessage(promptedFixture("complete.json")), promptedFixture("complete.json"))
        }
        val model = PromptedDecisionModel.create(
            TestDecisionService(sender.forPath(SenderPath.LEGACY)),
            LlmOptions(),
        )
        val request = com.embabel.agent.decision.api.DecisionRequest.builder()
            .timeout(Duration.ofMillis(500))
            .also { it.yesNo("safe", "Is this safe?") }
            .build()

        val result = CompletableFuture.supplyAsync { model.ask(request) }
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            val outcome = result.get(2, TimeUnit.SECONDS)
            assertThat(outcome).isInstanceOf(DecisionOutcome.Failure::class.java)
            assertThat((outcome as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.DeadlineExceeded)
            assertThat(sender.calls).isEqualTo(1)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `caller interruption is restored and cancels the facade wait`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val sender = RecordingDecisionSender {
            entered.countDown()
            release.await()
            LlmMessageResponse(AssistantMessage("{}"), "{}")
        }
        val model = PromptedDecisionModel.create(
            TestDecisionService(sender.forPath(SenderPath.LEGACY)),
            LlmOptions(),
        )
        val request = com.embabel.agent.decision.api.DecisionRequest.builder()
            .timeout(Duration.ofSeconds(5))
            .also { it.yesNo("safe", "Is this safe?") }
            .build()
        val outcome = AtomicReference<DecisionOutcome>()
        val interrupted = AtomicBoolean()
        val caller = Thread {
            outcome.set(model.ask(request))
            interrupted.set(Thread.currentThread().isInterrupted)
        }

        caller.start()
        try {
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
            caller.interrupt()
            caller.join(2_000)
            assertThat(caller.isAlive).isFalse()
            assertThat((outcome.get() as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.Cancelled)
            assertThat(interrupted).isTrue()
        } finally {
            release.countDown()
        }
    }

    private fun providerFrom(model: DecisionModel): DecisionProvider {
        val field = DecisionModel::class.java.getDeclaredField("decisionProvider")
        assertThat(field.trySetAccessible()).isTrue()
        return field.get(model) as DecisionProvider
    }

    private class MutablePreparedRequest(private val remaining: AtomicLong) : PreparedDecisionRequest {
        override val state: Map<String, Any?> = emptyMap()
        override val questions: List<PreparedQuestion> = listOf(
            object : PreparedQuestion {
                override val id = "yes"
                override val kind = DecisionKind.YES_NO
                override val question = "Should this proposition be revised?"
                override val support = listOf(
                    support("false", "false"),
                    support("true", "true"),
                )
            },
        )
        override val deadlineNanos: Long = Duration.ofSeconds(2).toNanos()
        override val recordPolicy: DecisionRecordPolicy = DecisionRecordPolicy.metadata()
        override val correlationId: String? = null
        override val requestId = "deterministic-deadline"
        override val questionFingerprint = "deterministic-fingerprint"
        override fun remainingNanos(): Long = remaining.get().coerceAtLeast(0)

        private fun support(supportId: String, supportLabel: String) = object : PreparedSupport {
            override val id = supportId
            override val label = supportLabel
        }
    }
}
