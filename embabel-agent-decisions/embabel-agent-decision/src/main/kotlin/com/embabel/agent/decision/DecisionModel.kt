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

import org.jetbrains.annotations.ApiStatus
import java.time.Duration

@ApiStatus.Experimental
class DecisionModel @JvmOverloads constructor(
    private val provider: DecisionProvider,
    private val defaultTimeout: Duration = Duration.ofSeconds(30),
    private val defaultRecordPolicy: DecisionRecordPolicy = DecisionRecordPolicy.metadata(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    init {
        require(!defaultTimeout.isNegative && !defaultTimeout.isZero) { "default timeout must be positive" }
    }

    fun withDefaults(defaultTimeout: Duration, defaultRecordPolicy: DecisionRecordPolicy): DecisionModel =
        DecisionModel(provider, defaultTimeout, defaultRecordPolicy, nanoTime)

    fun ask(request: DecisionRequest): DecisionOutcome {
        val start = nanoTime()
        val prepared = try {
            PreparedDecisionRequest.fromFacade(request, defaultTimeout, defaultRecordPolicy, start)
        } catch (_: IllegalArgumentException) {
            return failure(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST, null, request)
        }
        if (remaining(prepared) <= 0) return failure(CallFailure.DeadlineExceeded, DecisionSafeCode.DEADLINE_EXCEEDED, prepared)
        val raw = try {
            provider.invoke(prepared)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return failure(CallFailure.Cancelled, DecisionSafeCode.CANCELLED, prepared)
        } catch (_: Exception) {
            return failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE, prepared)
        }
        if (Thread.currentThread().isInterrupted) return failure(CallFailure.Cancelled, DecisionSafeCode.CANCELLED, prepared)
        if (remaining(prepared) <= 0) return failure(CallFailure.DeadlineExceeded, DecisionSafeCode.DEADLINE_EXCEEDED, prepared)
        raw.callFailure?.let { return failure(it, raw.safeCode ?: codeFor(it), prepared) }
        val rawAnswers = raw.answers ?: return failure(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST, prepared)
        val provenance = raw.provenance ?: return failure(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST, prepared)
        if (rawAnswers.map { it.keyId }.distinct().size != rawAnswers.size || rawAnswers.any { prepared.questions.none { question -> question.id == it.keyId } }) {
            return failure(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST, prepared)
        }
        val completed = DecisionProvenance.completed(provenance, prepared)
        val byId = rawAnswers.associateBy { it.keyId }
        val answers = prepared.questions.associate { question ->
            question.id to validate(question, byId[question.id])
        }
        return DecisionOutcome.Success.create(completed, record(prepared.recordPolicy, completed, answers), request.binding, answers)
    }

    private fun validate(question: PreparedQuestion, raw: RawAnswer?): KeyOutcome<*> {
        if (raw == null) return KeyOutcome.Failure.create<Any?>(KeyFailure.Missing, DecisionSafeCode.MISSING)
        raw.keyFailure?.let { return KeyOutcome.Failure.create<Any?>(it, raw.safeCode ?: codeFor(it)) }
        if (raw.kind != question.kind) return KeyOutcome.Failure.create<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        return when (question.kind) {
            DecisionKind.YES_NO -> yesNo(raw)
            DecisionKind.CHOICE, DecisionKind.RATING -> distribution(question, raw)
        }
    }

    private fun yesNo(raw: RawAnswer): KeyOutcome<*> {
        val probability = raw.pTrue ?: return KeyOutcome.Failure.create<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        if (!probability.isFinite() || probability !in 0.0..1.0) return KeyOutcome.Failure.create<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        val values: LinkedHashMap<Boolean, Double> = linkedMapOf(true to probability, false to 1.0 - probability)
        val maximizers: List<Boolean> = values.filterValues { it == values.values.max() }.keys.toList()
        if (raw.selectedId != null && raw.selectedId != maximizers.first().toString() && raw.selectedId != maximizers.last().toString()) {
            return KeyOutcome.Failure.create<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        }
        val selected: Boolean = when (raw.selectedId) {
            "true" -> true
            "false" -> false
            null -> maximizers.first()
            else -> return KeyOutcome.Failure.create<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        }
        return KeyOutcome.Success.create(selected, values, maximizers, maximizers.first(), null)
    }

    private fun distribution(question: PreparedQuestion, raw: RawAnswer): KeyOutcome<*> {
        val expected = question.support.map { it.id }
        if (raw.probabilities.map { it.supportId }.distinct().size != raw.probabilities.size || raw.probabilities.map { it.supportId }.toSet() != expected.toSet()) {
            return KeyOutcome.Failure.create<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        }
        val probabilities = raw.probabilities.associate { it.supportId to it.probability }
        if (probabilities.values.any { !it.isFinite() || it !in 0.0..1.0 } || kotlin.math.abs(probabilities.values.sum() - 1.0) > 1e-9) {
            return KeyOutcome.Failure.create<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        }
        val ordered = question.support.associate { it.id to probabilities.getValue(it.id) }
        val maximum = ordered.values.max()
        val maxIds = ordered.filterValues { it == maximum }.keys.toList()
        if (raw.selectedId != null && raw.selectedId !in maxIds) return KeyOutcome.Failure.create<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        val selected = raw.selectedId ?: maxIds.first()
        val expectedScore = if (question.kind == DecisionKind.RATING) ordered.values.withIndex().sumOf { (index, probability) -> index * probability } else null
        return KeyOutcome.Success.create(selected, ordered, maxIds, maxIds.first(), expectedScore)
    }

    private fun remaining(request: PreparedDecisionRequest): Long = request.deadlineNanos - nanoTime()

    private fun failure(failure: CallFailure, safeCode: DecisionSafeCode, prepared: PreparedDecisionRequest?, request: DecisionRequest? = null): DecisionOutcome.Failure {
        val provenance = DecisionProvenance.builder("decision-facade", EvidenceKind.VERBALIZED)
            .requestId(prepared?.requestId ?: java.util.UUID.randomUUID().toString())
            .apply { (prepared?.correlationId ?: request?.requestedCorrelationId)?.let { correlationId(it) } }
            .apply { prepared?.questionFingerprint?.let { questionFingerprint(it) } }
            .build()
        return DecisionOutcome.Failure.create(failure, safeCode, provenance, prepared?.let { record(it.recordPolicy, provenance, emptyMap()) })
    }

    private fun record(policy: DecisionRecordPolicy, provenance: DecisionProvenance, answers: Map<String, KeyOutcome<*>>): DecisionRecord? {
        if (policy.mode == RecordMode.NONE) return null
        val fields = linkedMapOf("envelopeVersion" to "1", "provider" to provenance.provider, "requestId" to (provenance.requestId ?: ""),
            "questionFingerprint" to (provenance.questionFingerprint ?: ""), "questionCount" to answers.size.toString())
        provenance.correlationId?.let { fields["correlationId"] = it }
        val failures = answers.values.filterIsInstance<KeyOutcome.Failure<*>>().map { it.safeCode.name }
        if (failures.isNotEmpty()) fields["safeCodes"] = failures.joinToString(",")
        if (policy.mode == RecordMode.FULL && "answerIds" in policy.allowlist) fields["answerIds"] = answers.keys.joinToString(",")
        val bounded = linkedMapOf<String, String>()
        var bytes = 0
        for ((key, value) in fields) {
            val size = (key + value).toByteArray().size
            if (policy.maxBytes == null || bytes + size <= policy.maxBytes) { bounded[key] = value; bytes += size }
        }
        return DecisionRecord.create(policy.mode, bounded)
    }
}

@ApiStatus.Experimental
object NoDecisionModel {
    @JvmStatic fun create(): DecisionModel = DecisionModel(DecisionProvider {
        RawDecisionOutcome.failure(CallFailure.Disabled, DecisionSafeCode.DISABLED)
    })
}

@ApiStatus.Experimental
class StubStep private constructor(internal val delay: Duration, internal val raw: RawDecisionOutcome) {
    companion object {
        @JvmStatic fun immediate(raw: RawDecisionOutcome) = StubStep(Duration.ZERO, raw)
        @JvmStatic fun after(delay: Duration, raw: RawDecisionOutcome): StubStep {
            require(!delay.isNegative) { "delay must not be negative" }
            return StubStep(delay, raw)
        }
    }
}

@ApiStatus.Experimental
object StubDecisionModel {
    @JvmStatic fun create(steps: List<StubStep>): DecisionModel {
        val scripted = steps.toList()
        require(scripted.isNotEmpty()) { "stub steps must not be empty" }
        val cursor = java.util.concurrent.atomic.AtomicInteger()
        return DecisionModel(DecisionProvider { prepared ->
            val step = scripted.getOrNull(cursor.getAndIncrement())
                ?: return@DecisionProvider RawDecisionOutcome.failure(CallFailure.Unsupported, DecisionSafeCode.UNSUPPORTED)
            val remaining = prepared.deadlineNanos - System.nanoTime()
            if (remaining <= 0 || step.delay.toNanos() > remaining) return@DecisionProvider RawDecisionOutcome.failure(CallFailure.DeadlineExceeded, DecisionSafeCode.DEADLINE_EXCEEDED)
            if (!step.delay.isZero) Thread.sleep(step.delay.toMillis())
            step.raw
        })
    }
}

private fun codeFor(failure: CallFailure): DecisionSafeCode = when (failure) {
    CallFailure.Disabled -> DecisionSafeCode.DISABLED; CallFailure.Unavailable -> DecisionSafeCode.UNAVAILABLE
    CallFailure.RejectedRequest -> DecisionSafeCode.REJECTED_REQUEST; CallFailure.DeadlineExceeded -> DecisionSafeCode.DEADLINE_EXCEEDED
    CallFailure.Cancelled -> DecisionSafeCode.CANCELLED; CallFailure.Unsupported -> DecisionSafeCode.UNSUPPORTED
}

private fun codeFor(failure: KeyFailure): DecisionSafeCode = when (failure) {
    KeyFailure.Missing -> DecisionSafeCode.MISSING; KeyFailure.Invalid -> DecisionSafeCode.INVALID; KeyFailure.Unsupported -> DecisionSafeCode.UNSUPPORTED
}
