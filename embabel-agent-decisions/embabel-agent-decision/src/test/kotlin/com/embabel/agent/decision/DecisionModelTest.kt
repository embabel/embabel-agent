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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class DecisionModelTest {
    @Test
    fun `named and defaulted copies preserve immutable registry identity independently`() {
        val original = DecisionModel(DecisionProvider {
            RawDecisionOutcome.failure(CallFailure.Disabled, DecisionSafeCode.DISABLED)
        })
        val named = original.named("revision", "custom")
        val defaulted = named.withDefaults(Duration.ofMillis(200), DecisionRecordPolicy.none())

        assertThat(original.name).isEqualTo("decision")
        assertThat(original.provider).isEqualTo("custom")
        assertThat(named.name).isEqualTo("revision")
        assertThat(named.provider).isEqualTo("custom")
        assertThat(defaulted.name).isEqualTo("revision")
        assertThat(defaulted.provider).isEqualTo("custom")
    }

    @Test
    fun `built in factories assign useful registry identities`() {
        assertThat(NoDecisionModel.create().name).isEqualTo("none")
        assertThat(NoDecisionModel.create().provider).isEqualTo("none")

        val raw = RawDecisionOutcome.failure(CallFailure.Disabled, DecisionSafeCode.DISABLED)
        assertThat(StubDecisionModel.create(listOf(StubStep.immediate(raw))).name).isEqualTo("stub")
        assertThat(StubDecisionModel.create(listOf(StubStep.immediate(raw))).provider).isEqualTo("stub")
    }

    @Test
    fun `initialization receipt closes only its created models once`() {
        val invocations = AtomicLong()
        val created = DecisionModel(DecisionProvider {
            invocations.incrementAndGet()
            RawDecisionOutcome.failure(CallFailure.Disabled, DecisionSafeCode.DISABLED)
        }).named("created", "custom")
        val unrelated = NoDecisionModel.create().named("unrelated", "custom")
        val initialization = DecisionModelInitialization(listOf(created))

        assertThat(initialization.createdModels).containsExactly(created)
        initialization.close()
        initialization.close()

        assertThat(created.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Failure::class.java)
        assertThat(invocations.get()).isZero()
        assertThat(unrelated.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Failure::class.java)
    }

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

        assertThat(answer.value).isEqualTo("IDENTICAL")
        assertThat(answer.maximizers).containsExactly("IDENTICAL", "SIMILAR")
        assertThat(answer.firstMaximizer).isEqualTo("IDENTICAL")
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
        assertThatThrownBy { DecisionRecordPolicy.full(1, setOf("answerIds")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DecisionRecordPolicy.full(10, setOf("*")) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { DecisionRecordPolicy.full(10, setOf("answerId")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unsupported record field")
    }

    @Test
    fun `uses request policy over defaults and transports the caller safe state projection exactly`() {
        val seen = mutableMapOf<String, Any?>()
        val model = DecisionModel(DecisionProvider { prepared ->
            seen.putAll(prepared.state)
            RawDecisionOutcome.success(listOf(RawAnswer.yesNo("safe", .7, "true")),
                DecisionProvenance.builder("test", EvidenceKind.DISTRIBUTION).build())
        }).withDefaults(Duration.ofMillis(200), DecisionRecordPolicy.none())
        val builder = DecisionRequest.builder().state(mapOf("token" to "caller-approved", "visible" to "yes"))
        builder.recordPolicy(DecisionRecordPolicy.metadata())
        builder.yesNo("safe", "safe?")

        val outcome = model.ask(builder.build()) as DecisionOutcome.Success
        assertThat(seen).containsEntry("visible", "yes").containsEntry("token", "caller-approved")
        assertThat(outcome.record).isNotNull
    }

    @Test
    fun `preserves nested caller projection and publishes immutable provider input`() {
        var nested: Map<String, Any?>? = null
        var mutationRejected = false
        var nestedMutationRejected = false
        var questionMutationRejected = false
        val builder = DecisionRequest.builder().state(mapOf("outer" to mapOf("nestedToken" to "secret", "safe" to "value", "list" to listOf("immutable"))))
        builder.yesNo("safe", "safe?")
        val outcome = DecisionModel(DecisionProvider { prepared ->
            @Suppress("UNCHECKED_CAST")
            nested = prepared.state["outer"] as Map<String, Any?>
            mutationRejected = try {
                (prepared.state as MutableMap<String, Any?>)["changed"] = "no"
                false
            } catch (_: UnsupportedOperationException) {
                true
            }
            nestedMutationRejected = try {
                ((prepared.state["outer"] as Map<*, *>)["list"] as MutableList<Any?>).add("no")
                false
            } catch (_: UnsupportedOperationException) {
                true
            }
            questionMutationRejected = try {
                (prepared.questions as MutableList<PreparedQuestion>).clear()
                false
            } catch (_: UnsupportedOperationException) {
                true
            }
            RawDecisionOutcome.success(listOf(RawAnswer.yesNo("safe", 1.0, "true")), DecisionProvenance.builder("test", EvidenceKind.DISTRIBUTION).build())
        }).ask(builder.build())

        assertThat(outcome).isInstanceOf(DecisionOutcome.Success::class.java)
        assertThat(nested).containsEntry("safe", "value").containsEntry("nestedToken", "secret")
        assertThat(mutationRejected).isTrue
        assertThat(nestedMutationRejected).isTrue
        assertThat(questionMutationRejected).isTrue
    }

    @Test
    fun `rejects non-string keys anywhere in nested state`() {
        val builder = DecisionRequest.builder()

        assertThatThrownBy {
            builder.state(mapOf("outer" to mapOf("valid" to "value", 7 to "unsafe")))
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("state map keys must be strings")
    }

    @Test
    fun `rejects cyclic and over budget state before provider dispatch`() {
        val cyclicMap = linkedMapOf<String, Any?>()
        cyclicMap["self"] = cyclicMap
        val cyclicList = mutableListOf<Any?>()
        cyclicList.add(cyclicList)
        listOf(cyclicMap, mapOf("list" to cyclicList)).forEach { state ->
            assertThatThrownBy { DecisionRequest.builder().state(state) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("cycle")
        }
        assertThatThrownBy {
            DecisionRequest.builder().state(mapOf("nested" to nestedState(DecisionRequestLimits.MAX_NESTING_DEPTH + 1)))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("nesting")
        assertThatThrownBy {
            DecisionRequest.builder().state(mapOf("nodes" to List(DecisionRequestLimits.MAX_STATE_NODES + 1) { it }))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("nodes")
        assertThatThrownBy {
            DecisionRequest.builder().state(mapOf("text" to "é".repeat(DecisionRequestLimits.MAX_STRING_BYTES / 2 + 1)))
        }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("UTF-8")
    }

    @Test
    fun `rejects question support and request byte budgets before provider dispatch`() {
        val invoked = AtomicLong()
        val model = DecisionModel(DecisionProvider {
            invoked.incrementAndGet()
            RawDecisionOutcome.failure(CallFailure.Disabled, DecisionSafeCode.DISABLED)
        })
        val tooManyQuestions = DecisionRequest.builder()
        repeat(DecisionRequestLimits.MAX_QUESTIONS) { tooManyQuestions.yesNo("q$it", "question $it") }
        assertThatThrownBy { tooManyQuestions.yesNo("overflow", "overflow") }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("questions")

        val tooManySupports = List(DecisionRequestLimits.MAX_SUPPORT_PER_QUESTION + 1) {
            DecisionOption.of("s$it", it, "support $it")
        }
        assertThatThrownBy { DecisionRequest.builder().choice("q", "question", tooManySupports) }
            .isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("support")

        val builder = DecisionRequest.builder().state(mapOf("chunks" to List(66) { "é".repeat(8_000) }))
        builder.yesNo("q", "question")
        val rejected = model.ask(builder.build()) as DecisionOutcome.Failure
        assertThat(rejected.failure).isEqualTo(CallFailure.RejectedRequest)
        assertThat(invoked.get()).isZero()

        val accepted = DecisionRequest.builder().state(mapOf("chunks" to List(65) { "é".repeat(8_000) }))
        accepted.yesNo("q", "question")
        model.ask(accepted.build())
        assertThat(invoked.get()).isEqualTo(1)
    }

    @Test
    fun `correlation ids are bounded opaque single line values`() {
        listOf("dice:proposition:42", "revision-2026_09.23", "relation/a-b").forEach { value ->
            val builder = DecisionRequest.builder().correlationId(value)
            builder.yesNo("q", "question")
            assertThat(builder.build()).isNotNull
            assertThat(DecisionProvenance.builder("dice", EvidenceKind.DISTRIBUTION).correlationId(value).build().correlationId)
                .isEqualTo(value)
        }
        listOf("bad\nvalue", "bad\rvalue", "bad\u0000value", "bad\u0085value", "bad\u200bvalue", "bad\u2028value", "bad\u2029value").forEach { value ->
            assertThatThrownBy { DecisionRequest.builder().correlationId(value) }
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { DecisionProvenance.builder("dice", EvidenceKind.DISTRIBUTION).correlationId(value) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `bounds a noncooperative provider at the request deadline`() {
        val builder = DecisionRequest.builder().timeout(Duration.ofMillis(20))
        builder.yesNo("safe", "safe?")
        val started = System.nanoTime()
        val outcome = DecisionModel(DecisionProvider {
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                // Deliberately ignore interruption: the facade still returns on its deadline.
                Thread.sleep(500)
            }
            RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE)
        }).ask(builder.build())
        val elapsed = Duration.ofNanos(System.nanoTime() - started)

        assertThat(outcome).isInstanceOf(DecisionOutcome.Failure::class.java)
        assertThat((outcome as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.DeadlineExceeded)
        assertThat(elapsed).isLessThan(Duration.ofMillis(250))
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

    @Test
    fun `uses one injected monotonic deadline and discards a late success`() {
        val clock = AtomicLong(1_000)
        val builder = DecisionRequest.builder()
        builder.yesNo("safe", "safe?")
        val model = modelWithClock(DecisionProvider {
            clock.set(1_000 + Duration.ofSeconds(5).toNanos())
            RawDecisionOutcome.success(listOf(RawAnswer.yesNo("safe", 1.0, "true")), DecisionProvenance.builder("test", EvidenceKind.DISTRIBUTION).build())
        }, Duration.ofSeconds(5), DecisionRecordPolicy.metadata(), clock::get)

        val result = model.ask(builder.build())

        assertThat(result).isInstanceOf(DecisionOutcome.Failure::class.java)
        assertThat((result as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.DeadlineExceeded)
    }

    @Test
    fun `computes deadlines across negative origins and signed wrap`() {
        listOf(Long.MIN_VALUE, Long.MAX_VALUE - 10).forEach { origin ->
            val clock = AtomicLong(origin)
            val builder = DecisionRequest.builder()
            builder.yesNo("safe", "safe?")
            val request = builder.build()
            val success = RawDecisionOutcome.success(
                listOf(RawAnswer.yesNo("safe", 1.0, "true")),
                DecisionProvenance.builder("test", EvidenceKind.DISTRIBUTION).build(),
            )
            val model = modelWithClock(DecisionProvider {
                success
            }, Duration.ofSeconds(5), DecisionRecordPolicy.metadata(), clock::get)

            assertThat(model.ask(request)).isInstanceOf(DecisionOutcome.Success::class.java)

            clock.set(origin)
            val lateModel = modelWithClock(DecisionProvider {
                clock.set(origin + Duration.ofSeconds(5).toNanos())
                RawDecisionOutcome.success(
                    listOf(RawAnswer.yesNo("safe", 1.0, "true")),
                    DecisionProvenance.builder("test", EvidenceKind.DISTRIBUTION).build(),
                )
            }, Duration.ofSeconds(5), DecisionRecordPolicy.metadata(), clock::get)

            val late = lateModel.ask(request) as DecisionOutcome.Failure
            assertThat(late.failure).isEqualTo(CallFailure.DeadlineExceeded)
        }
    }

    @Test
    fun `rejects foreign keys and validates all raw failure kinds`() {
        val first = DecisionRequest.builder().also { it.yesNo("safe", "safe?") }
        val second = DecisionRequest.builder().also { it.yesNo("safe", "safe?") }
        val firstKey = first.yesNo("other", "other?")
        val foreign = second.yesNo("other", "other?")
        val raw = RawDecisionOutcome.success(listOf(RawAnswer.yesNo("safe", 1.0, "true"), RawAnswer.yesNo("other", 1.0, "true")), DecisionProvenance.builder("test", EvidenceKind.DISTRIBUTION).build())
        val success = DecisionModel(DecisionProvider { raw }).ask(first.build()) as DecisionOutcome.Success

        assertThatThrownBy { success.answer(foreign) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(success.answer(firstKey)).isInstanceOf(KeyOutcome.Success::class.java)
        CallFailure.entries.forEach { failure ->
            val outcome = DecisionModel(DecisionProvider { RawDecisionOutcome.failure(failure, DecisionSafeCode.UNAVAILABLE) }).ask(yesNoRequest()) as DecisionOutcome.Failure
            assertThat(outcome.failure).isEqualTo(failure)
            assertThat(outcome.safeCode).isEqualTo(canonical(failure))
        }
        KeyFailure.entries.forEach { failure ->
            val request = DecisionRequest.builder()
            val key = request.yesNo("safe", "safe?")
            val outcome = DecisionModel(DecisionProvider {
                RawDecisionOutcome.success(listOf(RawAnswer.failure("safe", failure, DecisionSafeCode.INVALID)), DecisionProvenance.builder("test", EvidenceKind.DISTRIBUTION).build())
            }).ask(request.build()) as DecisionOutcome.Success
            val answer = outcome.answer(key)
            assertThat(answer).isInstanceOf(KeyOutcome.Failure::class.java)
            assertThat((answer as KeyOutcome.Failure).safeCode).isEqualTo(canonical(failure))
        }
    }

    @Test
    fun `rejects invalid numeric distributions and nonmaximizing selections`() {
        listOf(
            listOf(RawProbability.of("a", Double.NaN), RawProbability.of("b", 0.0)),
            listOf(RawProbability.of("a", -0.1), RawProbability.of("b", 1.1)),
            listOf(RawProbability.of("a", .3), RawProbability.of("b", .3)),
        ).forEach { probabilities ->
            val request = DecisionRequest.builder(); val key = request.choice("choice", "choice?", listOf(DecisionOption.of("a", "A", "a"), DecisionOption.of("b", "B", "b")))
            val outcome = DecisionModel(DecisionProvider {
                RawDecisionOutcome.success(listOf(RawAnswer.distribution("choice", DecisionKind.CHOICE, probabilities, "a")), DecisionProvenance.builder("test", EvidenceKind.DISTRIBUTION).build())
            }).ask(request.build()) as DecisionOutcome.Success
            assertThat(outcome.answer(key)).isInstanceOf(KeyOutcome.Failure::class.java)
        }
        val request = DecisionRequest.builder(); val key = request.choice("choice", "choice?", listOf(DecisionOption.of("a", "A", "a"), DecisionOption.of("b", "B", "b")))
        val outcome = DecisionModel(DecisionProvider {
            RawDecisionOutcome.success(listOf(RawAnswer.distribution("choice", DecisionKind.CHOICE, listOf(RawProbability.of("a", .8), RawProbability.of("b", .2)), "b")), DecisionProvenance.builder("test", EvidenceKind.DISTRIBUTION).build())
        }).ask(request.build()) as DecisionOutcome.Success
        assertThat(outcome.answer(key)).isInstanceOf(KeyOutcome.Failure::class.java)
    }

    @Test
    fun `bounds every record projection including full allowlisted records`() {
        val builder = DecisionRequest.builder().recordPolicy(DecisionRecordPolicy.full(4096, setOf("answerIds")))
        builder.correlationId("x".repeat(256))
        builder.yesNo("safe", "safe?")
        val outcome = DecisionModel(DecisionProvider {
            RawDecisionOutcome.success(listOf(RawAnswer.yesNo("safe", .5, "false")), DecisionProvenance.builder("provider", EvidenceKind.DISTRIBUTION).build())
        }).ask(builder.build()) as DecisionOutcome.Success

        val record = requireNotNull(outcome.record)
        assertThat(record.fields).containsEntry("schemaVersion", "1").containsEntry("answerIds", "false")
        assertThatThrownBy { (record.fields as MutableMap<String, String>)["x"] = "y" }.isInstanceOf(UnsupportedOperationException::class.java)
    }

    @Test
    fun `failure records use prepared question count and canonical call safe code`() {
        CallFailure.entries.forEach { failure ->
            val builder = DecisionRequest.builder().recordPolicy(DecisionRecordPolicy.metadata())
            builder.yesNo("first", "first?")
            builder.yesNo("second", "second?")
            val outcome = DecisionModel(DecisionProvider {
                RawDecisionOutcome.failure(failure, DecisionSafeCode.INVALID)
            }).ask(builder.build()) as DecisionOutcome.Failure

            assertThat(outcome.safeCode).isEqualTo(canonical(failure))
            assertThat(outcome.record!!.fields)
                .containsEntry("questionCount", "2")
                .containsEntry("safeCodes", canonical(failure).name)
            assertThat(outcome.record!!.fields.values.joinToString()).doesNotContain("first?").doesNotContain("second?")
        }
    }

    @Test
    fun `full record byte limit covers deterministic JSON encoding`() {
        val correlationId = "\"\\é".repeat(64).take(256)
        assertThat(correlationId.toByteArray(Charsets.UTF_8)).hasSize(256)
        fun ask(maxBytes: Int): DecisionOutcome.Success {
            val builder = DecisionRequest.builder()
                .recordPolicy(DecisionRecordPolicy.full(maxBytes, setOf("answerIds")))
                .correlationId(correlationId)
            builder.yesNo("quoted", "quoted?")
            return DecisionModel(DecisionProvider {
                RawDecisionOutcome.success(
                    listOf(RawAnswer.yesNo("quoted", 1.0, "true")),
                    DecisionProvenance.builder("provider-\"\\é", EvidenceKind.DISTRIBUTION).build(),
                )
            }).ask(builder.build()) as DecisionOutcome.Success
        }

        val complete = ask(4096)
        assertThat(complete.record!!.fields)
            .containsEntry("correlationId", correlationId)
            .containsEntry("provider", "provider-\"\\é")
            .containsEntry("answerIds", "true")
        val maxBytes = serializedRecordBytes(complete.record!!.fields) - 1
        val bounded = ask(maxBytes)
        assertThat(serializedRecordBytes(bounded.record!!.fields)).isLessThanOrEqualTo(maxBytes)
    }

    @Test
    fun `rejects mutable number state before provider invocation`() {
        val invoked = AtomicLong()
        assertThatThrownBy {
            DecisionRequest.builder().state(mapOf("counter" to java.util.concurrent.atomic.AtomicInteger(7)))
        }.isInstanceOf(IllegalArgumentException::class.java)
        val request = DecisionRequest.builder().also { it.yesNo("safe", "safe?") }.build()
        DecisionModel(DecisionProvider { invoked.incrementAndGet(); RawDecisionOutcome.failure(CallFailure.Disabled, DecisionSafeCode.DISABLED) }).ask(request)
        assertThat(invoked.get()).isEqualTo(1)
    }

    @Test
    fun `isolates poisoned capacity per model and cancels interruption`() {
        val workersStarted = CountDownLatch(DecisionExecutionSupport.MAX_WORKERS)
        val release = CountDownLatch(1)
        val workersFinished = CountDownLatch(DecisionExecutionSupport.MAX_WORKERS)
        val callersFinished = CountDownLatch(DecisionExecutionSupport.MAX_WORKERS)
        val poisonedWorkerNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val provider = DecisionProvider {
            poisonedWorkerNames += Thread.currentThread().name
            workersStarted.countDown()
            try {
                while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
                RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE)
            } finally {
                workersFinished.countDown()
            }
        }
        val blockingRequest = DecisionRequest.builder().also { it.timeout(Duration.ofSeconds(2)); it.yesNo("safe", "safe?") }.build()
        val saturatedRequest = DecisionRequest.builder().also { it.timeout(Duration.ofMillis(100)); it.yesNo("safe", "safe?") }.build()
        val poisoned = DecisionModel(provider)
        val callers = List(DecisionExecutionSupport.MAX_WORKERS) {
            Thread {
                try {
                    poisoned.ask(blockingRequest)
                } finally {
                    callersFinished.countDown()
                }
            }
        }
        try {
            callers.forEach(Thread::start)
            assertThat(workersStarted.await(1, TimeUnit.SECONDS)).isTrue
            (1..24).forEach {
                val outcome = poisoned.ask(saturatedRequest) as DecisionOutcome.Failure
                assertThat(outcome.failure).isEqualTo(CallFailure.Unavailable)
            }
            val healthy = DecisionModel(DecisionProvider {
                RawDecisionOutcome.success(
                    listOf(RawAnswer.yesNo("yes", 1.0, "true")),
                    DecisionProvenance.builder("healthy", EvidenceKind.DISTRIBUTION).build(),
                )
            })
            assertThat(healthy.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
            assertThat(poisonedWorkerNames).hasSize(DecisionExecutionSupport.MAX_WORKERS)
            val disabled = NoDecisionModel.create().ask(yesNoRequest()) as DecisionOutcome.Failure
            assertThat(disabled.failure).isEqualTo(CallFailure.Disabled)
            assertThat(disabled.safeCode).isEqualTo(DecisionSafeCode.DISABLED)
            release.countDown()
            assertThat(workersFinished.await(1, TimeUnit.SECONDS)).isTrue
            assertThat(callersFinished.await(1, TimeUnit.SECONDS)).isTrue

            val result = AtomicReference<DecisionOutcome>()
            val interruptStarted = CountDownLatch(1)
            val interruptRelease = CountDownLatch(1)
            val interruptWorkerFinished = CountDownLatch(1)
            val caller = Thread { result.set(DecisionModel(DecisionProvider {
                interruptStarted.countDown()
                try {
                    while (interruptRelease.count > 0) try { interruptRelease.await() } catch (_: InterruptedException) { }
                    RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE)
                } finally {
                    interruptWorkerFinished.countDown()
                }
            }).ask(blockingRequest)) }
            try {
                caller.start()
                assertThat(interruptStarted.await(1, TimeUnit.SECONDS)).isTrue
                caller.interrupt()
                caller.join(500)
                assertThat(result.get()).isInstanceOf(DecisionOutcome.Failure::class.java)
                assertThat((result.get() as DecisionOutcome.Failure).failure).isEqualTo(CallFailure.Cancelled)
            } finally {
                caller.interrupt()
                interruptRelease.countDown()
            }
            assertThat(interruptWorkerFinished.await(1, TimeUnit.SECONDS)).isTrue
        } finally {
            release.countDown()
            callers.forEach(Thread::interrupt)
        }
    }

    @Test
    fun `fresh model succeeds after a poisoned model reaches every deadline`() {
        val entered = CountDownLatch(DecisionExecutionSupport.MAX_WORKERS)
        val start = CountDownLatch(1)
        val poisoned = DecisionModel(DecisionProvider {
            entered.countDown()
            while (true) {
                java.util.concurrent.locks.LockSupport.parkNanos(Duration.ofMillis(1).toNanos())
                Thread.interrupted()
            }
            @Suppress("UNREACHABLE_CODE")
            RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE)
        })
        val request = DecisionRequest.builder().also {
            it.timeout(Duration.ofMillis(500))
            it.yesNo("yes", "yes?")
        }.build()
        val callers = List(DecisionExecutionSupport.MAX_WORKERS) {
            Thread {
                start.await()
                poisoned.ask(request)
            }.also(Thread::start)
        }
        start.countDown()
        assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue
        callers.forEach { it.join(1_000) }
        assertThat(callers).allMatch { !it.isAlive }

        val healthy = DecisionModel(DecisionProvider {
            RawDecisionOutcome.success(
                listOf(RawAnswer.yesNo("yes", 1.0, "true")),
                DecisionProvenance.builder("healthy", EvidenceKind.DISTRIBUTION).build(),
            )
        })
        assertThat(healthy.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
    }

    private fun nestedState(depth: Int): Any? = if (depth == 0) "leaf" else mapOf("next" to nestedState(depth - 1))

    private fun serializedRecordBytes(fields: Map<String, String>): Int = fields.entries.joinToString(",", "{", "}") { (key, value) ->
        "${jsonString(key)}:${jsonString(value)}"
    }.toByteArray(Charsets.UTF_8).size

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
        append('"')
    }

    @Test
    fun `stub preserves immediate and delayed script while external providers saturate workers`() {
        val workersStarted = CountDownLatch(DecisionExecutionSupport.MAX_WORKERS)
        val releaseWorkers = CountDownLatch(1)
        val blockingProvider = DecisionProvider {
            workersStarted.countDown()
            while (releaseWorkers.count > 0) try {
                releaseWorkers.await()
            } catch (_: InterruptedException) {
                // Model an external provider that cannot cooperate with cancellation.
            }
            RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE)
        }
        val blockingRequest = DecisionRequest.builder().also {
            it.timeout(Duration.ofSeconds(5))
            it.yesNo("blocked", "blocked?")
        }.build()
        val callers = List(DecisionExecutionSupport.MAX_WORKERS) {
            Thread { DecisionModel(blockingProvider).ask(blockingRequest) }
        }
        val scriptedSuccess = RawDecisionOutcome.success(
            listOf(RawAnswer.yesNo("yes", 1.0, "true")),
            DecisionProvenance.builder("stub", EvidenceKind.DISTRIBUTION).build(),
        )
        val stub = StubDecisionModel.create(listOf(
            StubStep.immediate(scriptedSuccess),
            StubStep.after(Duration.ofMillis(10), scriptedSuccess),
            StubStep.immediate(RawDecisionOutcome.failure(CallFailure.Disabled, DecisionSafeCode.UNAVAILABLE)),
        ))

        try {
            callers.forEachIndexed { index, caller ->
                caller.start()
                val expected = DecisionExecutionSupport.MAX_WORKERS - index - 1L
                val waitUntil = System.nanoTime() + Duration.ofSeconds(1).toNanos()
                while (workersStarted.count > expected && System.nanoTime() - waitUntil < 0) Thread.yield()
                assertThat(workersStarted.count).isEqualTo(expected)
            }

            assertThat(stub.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
            assertThat(stub.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)
            val normalized = stub.ask(yesNoRequest()) as DecisionOutcome.Failure
            assertThat(normalized.failure).isEqualTo(CallFailure.Disabled)
            assertThat(normalized.safeCode).isEqualTo(DecisionSafeCode.DISABLED)
            val exhausted = stub.ask(yesNoRequest()) as DecisionOutcome.Failure
            assertThat(exhausted.failure).isEqualTo(CallFailure.Unsupported)
            assertThat(exhausted.safeCode).isEqualTo(DecisionSafeCode.UNSUPPORTED)
        } finally {
            releaseWorkers.countDown()
            callers.forEach { it.join(1_000) }
        }
    }

    @Test
    fun `stub delayed steps honor deadline and caller cancellation`() {
        val success = RawDecisionOutcome.success(
            listOf(RawAnswer.yesNo("yes", 1.0, "true")),
            DecisionProvenance.builder("stub", EvidenceKind.DISTRIBUTION).build(),
        )
        val deadlineRequest = DecisionRequest.builder().also {
            it.timeout(Duration.ofMillis(10))
            it.yesNo("yes", "yes?")
        }.build()
        val deadlineStub = StubDecisionModel.create(listOf(
            StubStep.after(Duration.ofMillis(20), success),
            StubStep.immediate(success),
        ))

        val deadline = deadlineStub.ask(deadlineRequest) as DecisionOutcome.Failure
        assertThat(deadline.failure).isEqualTo(CallFailure.DeadlineExceeded)
        assertThat(deadline.safeCode).isEqualTo(DecisionSafeCode.DEADLINE_EXCEEDED)
        assertThat(deadlineStub.ask(yesNoRequest())).isInstanceOf(DecisionOutcome.Success::class.java)

        val cancellationStub = StubDecisionModel.create(listOf(StubStep.after(Duration.ofSeconds(5), success)))
        val cancellationRequest = DecisionRequest.builder().also {
            it.timeout(Duration.ofSeconds(10))
            it.yesNo("yes", "yes?")
        }.build()
        val result = AtomicReference<DecisionOutcome>()
        val interrupted = AtomicReference<Boolean>()
        val caller = Thread {
            result.set(cancellationStub.ask(cancellationRequest))
            interrupted.set(Thread.currentThread().isInterrupted)
        }

        caller.start()
        val waitUntil = System.nanoTime() + Duration.ofSeconds(1).toNanos()
        while (caller.state != Thread.State.TIMED_WAITING && System.nanoTime() - waitUntil < 0) Thread.yield()
        assertThat(caller.state).isEqualTo(Thread.State.TIMED_WAITING)
        caller.interrupt()
        caller.join(1_000)

        val cancelled = result.get() as DecisionOutcome.Failure
        assertThat(cancelled.failure).isEqualTo(CallFailure.Cancelled)
        assertThat(cancelled.safeCode).isEqualTo(DecisionSafeCode.CANCELLED)
        assertThat(interrupted.get()).isTrue
    }

    private fun yesNoRequest(): DecisionRequest {
        val builder = DecisionRequest.builder()
        builder.yesNo("yes", "yes?")
        return builder.build()
    }

    private fun modelWithClock(provider: DecisionProvider, timeout: Duration, policy: DecisionRecordPolicy, clock: () -> Long): DecisionModel {
        val model = DecisionModel(provider).withDefaults(timeout, policy)
        DecisionModel::class.java.getDeclaredField("clock").apply { isAccessible = true }.set(model, clock)
        return model
    }

    private fun canonical(failure: CallFailure) = when (failure) {
        CallFailure.Disabled -> DecisionSafeCode.DISABLED; CallFailure.Unavailable -> DecisionSafeCode.UNAVAILABLE; CallFailure.RejectedRequest -> DecisionSafeCode.REJECTED_REQUEST; CallFailure.DeadlineExceeded -> DecisionSafeCode.DEADLINE_EXCEEDED; CallFailure.Cancelled -> DecisionSafeCode.CANCELLED; CallFailure.Unsupported -> DecisionSafeCode.UNSUPPORTED
    }

    private fun canonical(failure: KeyFailure) = when (failure) {
        KeyFailure.Missing -> DecisionSafeCode.MISSING; KeyFailure.Invalid -> DecisionSafeCode.INVALID; KeyFailure.Unsupported -> DecisionSafeCode.UNSUPPORTED
    }
}
