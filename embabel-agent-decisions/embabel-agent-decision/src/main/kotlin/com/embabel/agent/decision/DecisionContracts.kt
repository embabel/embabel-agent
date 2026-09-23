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
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@ApiStatus.Experimental enum class DecisionKind { YES_NO, CHOICE, RATING }
@ApiStatus.Experimental enum class EvidenceKind { DISTRIBUTION, VERBALIZED }
@ApiStatus.Experimental enum class RecordMode { NONE, METADATA, FULL }
@ApiStatus.Experimental enum class DecisionSafeCode { DISABLED, UNAVAILABLE, REJECTED_REQUEST, DEADLINE_EXCEEDED, CANCELLED, UNSUPPORTED, MISSING, INVALID }
@ApiStatus.Experimental enum class CallFailure { Disabled, Unavailable, RejectedRequest, DeadlineExceeded, Cancelled, Unsupported }
@ApiStatus.Experimental enum class KeyFailure { Missing, Invalid, Unsupported }

@ApiStatus.Experimental
class DecisionOption<T> private constructor(val id: String, val value: T, val label: String) {
    init { require(id.isNotBlank() && id.length <= 256 && id.none { it.isWhitespace() }) { "support id must be a nonblank opaque token" }; require(label.isNotBlank()) { "label must not be blank" } }
    companion object { @JvmStatic fun <T> of(id: String, value: T, label: String) = DecisionOption(id, value, label) }
}

@ApiStatus.Experimental sealed interface DecisionKey<T> { val id: String }
@ApiStatus.Experimental interface YesNoKey : DecisionKey<Boolean>
@ApiStatus.Experimental interface ChoiceKey<T> : DecisionKey<T>
@ApiStatus.Experimental interface RatingKey<T> : DecisionKey<T>
private class YesNoToken(override val id: String, val binding: String, val question: String) : YesNoKey
private class ChoiceToken<T>(override val id: String, val binding: String, val question: String, val options: List<DecisionOption<T>>) : ChoiceKey<T>
private class RatingToken<T>(override val id: String, val binding: String, val question: String, val options: List<DecisionOption<T>>) : RatingKey<T>

@ApiStatus.Experimental
class DecisionRecordPolicy private constructor(val mode: RecordMode, val maxBytes: Int?, val allowlist: Set<String>) {
    companion object {
        @JvmStatic fun none() = DecisionRecordPolicy(RecordMode.NONE, null, emptySet())
        @JvmStatic fun metadata() = DecisionRecordPolicy(RecordMode.METADATA, null, emptySet())
        @JvmStatic fun full(maxBytes: Int, allowlist: Set<String>): DecisionRecordPolicy {
            require(maxBytes in 1..MAX_RECORD_BYTES) { "maxBytes must be between 1 and $MAX_RECORD_BYTES" }
            require(allowlist.isNotEmpty()) { "full records require an explicit allowlist" }
            val copied = allowlist.map { require(it.isNotBlank() && '*' !in it) { "allowlist entries must be explicit" }; it }.toSet()
            return DecisionRecordPolicy(RecordMode.FULL, maxBytes, Collections.unmodifiableSet(copied))
        }
    }
}

@ApiStatus.Experimental
interface DecisionRequest {
    @ApiStatus.Experimental class Builder {
        private val binding = UUID.randomUUID().toString(); private val questions = mutableListOf<QuestionData<*>>()
        private var state: Map<String, Any?> = emptyMap(); private var timeout: Duration? = null; private var policy: DecisionRecordPolicy? = null; private var correlationId: String? = null
        fun state(values: Map<String, Any?>) = apply { state = snapshot(values) }
        fun correlationId(correlationId: String) = apply { require(correlationId.isNotBlank() && correlationId.toByteArray(StandardCharsets.UTF_8).size <= 256) { "correlationId must be nonblank and at most 256 UTF-8 bytes" }; this.correlationId = correlationId }
        fun timeout(timeout: Duration) = apply { require(!timeout.isNegative && !timeout.isZero); try { timeout.toNanos() } catch (_: ArithmeticException) { throw IllegalArgumentException("timeout too large") }; this.timeout = timeout }
        fun recordPolicy(policy: DecisionRecordPolicy) = apply { this.policy = policy }
        fun yesNo(id: String, question: String): YesNoKey { question(id, question); return YesNoToken(id, binding, question).also { questions += QuestionData(it, DecisionKind.YES_NO, emptyList()) } }
        fun <T> choice(id: String, question: String, options: List<DecisionOption<T>>): ChoiceKey<T> { question(id, question); options(options); return ChoiceToken(id, binding, question, frozen(options)).also { questions += QuestionData(it, DecisionKind.CHOICE, it.options) } }
        fun <T> rating(id: String, question: String, levels: List<DecisionOption<T>>): RatingKey<T> { question(id, question); options(levels); return RatingToken(id, binding, question, frozen(levels)).also { questions += QuestionData(it, DecisionKind.RATING, it.options) } }
        fun build(): DecisionRequest { require(questions.isNotEmpty()) { "a decision request needs at least one question" }; return RequestToken(RequestData(binding, state, frozen(questions), timeout, policy, correlationId)) }
        private fun question(id: String, question: String) { require(id.isNotBlank() && id.length <= 256 && id.none { it.isWhitespace() }) { "key id must be a nonblank opaque token" }; require(question.isNotBlank()) { "question must not be blank" }; require(questions.none { it.id == id }) { "duplicate key id" } }
        private fun <T> options(options: List<DecisionOption<T>>) { require(options.isNotEmpty()) { "support must not be empty" }; require(options.map { it.id }.distinct().size == options.size) { "duplicate support id" }; require(options.map { it.value }.distinct().size == options.size) { "support values must be distinct" } }
        private fun snapshot(values: Map<String, Any?>): Map<String, Any?> = Collections.unmodifiableMap(LinkedHashMap<String, Any?>().apply { values.forEach { (key, value) -> if (!listOf("secret", "password", "token", "credential", "apikey", "api_key").any { key.contains(it, true) }) put(key, snapshotValue(value)) } })
        private fun snapshotValue(value: Any?): Any? = when (value) { null, is String, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double, is java.math.BigInteger, is java.math.BigDecimal -> value; is Number -> throw IllegalArgumentException("state number values must be immutable primitives"); is Map<*, *> -> snapshot(value.entries.filter { it.key is String }.associate { it.key as String to it.value }); is Iterable<*> -> frozen(value.map { snapshotValue(it) }); is Array<*> -> frozen(value.map { snapshotValue(it) }); else -> throw IllegalArgumentException("state values must be scalar, map, or collection") }
        private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    }
    companion object { @JvmStatic fun builder() = Builder() }
}
private class RequestToken(val data: RequestData) : DecisionRequest

@ApiStatus.Experimental interface PreparedDecisionRequest { val state: Map<String, Any?>; val questions: List<PreparedQuestion>; val deadlineNanos: Long; val recordPolicy: DecisionRecordPolicy; val correlationId: String?; val requestId: String; val questionFingerprint: String; fun remainingNanos(): Long }
@ApiStatus.Experimental interface PreparedQuestion { val id: String; val kind: DecisionKind; val question: String; val support: List<PreparedSupport> }
@ApiStatus.Experimental interface PreparedSupport { val id: String; val label: String }
private data class PreparedSupportData(override val id: String, override val label: String) : PreparedSupport
private data class PreparedQuestionData(override val id: String, override val kind: DecisionKind, override val question: String, override val support: List<PreparedSupport>) : PreparedQuestion
private data class PreparedData(override val state: Map<String, Any?>, override val questions: List<PreparedQuestion>, override val deadlineNanos: Long, override val recordPolicy: DecisionRecordPolicy, override val correlationId: String?, override val requestId: String, override val questionFingerprint: String, private val clock: () -> Long) : PreparedDecisionRequest { override fun remainingNanos(): Long { val now = clock(); return if (deadlineNanos <= now) 0 else deadlineNanos - now } }

@ApiStatus.Experimental interface DecisionUsage { val inputTokens: Int?; val outputTokens: Int? }
private data class UsageData(override val inputTokens: Int?, override val outputTokens: Int?) : DecisionUsage
@ApiStatus.Experimental
class DecisionProvenance private constructor(val provider: String, val evidenceKind: EvidenceKind, val requestedModel: String?, val resolvedModel: String?, val modelVersion: String?, val adapterVersion: String?, val promptVersion: String?, val requestId: String?, val correlationId: String?, val questionFingerprint: String?, val timestamp: Instant?, val usage: DecisionUsage?) {
    @ApiStatus.Experimental interface Builder {
        fun requestedModel(value: String): Builder; fun resolvedModel(value: String): Builder; fun modelVersion(value: String): Builder; fun adapterVersion(value: String): Builder; fun promptVersion(value: String): Builder; fun requestId(value: String): Builder; fun correlationId(value: String): Builder; fun questionFingerprint(value: String): Builder; fun timestamp(value: Instant): Builder; fun usage(inputTokens: Int, outputTokens: Int): Builder; fun build(): DecisionProvenance
    }
    private class BuilderData(private val provider: String, private val evidenceKind: EvidenceKind) : Builder {
        private var requestedModel: String? = null; private var resolvedModel: String? = null; private var modelVersion: String? = null; private var adapterVersion: String? = null; private var promptVersion: String? = null; private var requestId: String? = null; private var correlationId: String? = null; private var fingerprint: String? = null; private var timestamp: Instant? = null; private var usage: DecisionUsage? = null
        override fun requestedModel(value: String) = apply { requestedModel = text(value) }; override fun resolvedModel(value: String) = apply { resolvedModel = text(value) }; override fun modelVersion(value: String) = apply { modelVersion = text(value) }; override fun adapterVersion(value: String) = apply { adapterVersion = text(value) }; override fun promptVersion(value: String) = apply { promptVersion = text(value) }; override fun requestId(value: String) = apply { requestId = text(value) }; override fun correlationId(value: String) = apply { require(value.isNotBlank() && value.toByteArray(StandardCharsets.UTF_8).size <= 256); correlationId = value }; override fun questionFingerprint(value: String) = apply { fingerprint = text(value) }; override fun timestamp(value: Instant) = apply { timestamp = value }; override fun usage(inputTokens: Int, outputTokens: Int) = apply { require(inputTokens >= 0 && outputTokens >= 0); usage = UsageData(inputTokens, outputTokens) }
        override fun build() = DecisionProvenance(provider, evidenceKind, requestedModel, resolvedModel, modelVersion, adapterVersion, promptVersion, requestId, correlationId, fingerprint, timestamp, usage)
        private fun text(value: String): String { require(value.isNotBlank() && value.toByteArray(StandardCharsets.UTF_8).size <= 256 && value.none { it == '\n' || it == '\r' }); return value }
    }
    companion object { @JvmStatic fun builder(provider: String, evidenceKind: EvidenceKind): Builder { require(provider.isNotBlank() && provider.toByteArray(StandardCharsets.UTF_8).size <= 256 && provider.none { it == '\n' || it == '\r' }); return BuilderData(provider, evidenceKind) } }
}

@ApiStatus.Experimental class RawProbability private constructor(val supportId: String, val probability: Double) { companion object { @JvmStatic fun of(supportId: String, probability: Double) = RawProbability(supportId, probability) } }
@ApiStatus.Experimental class RawAnswer private constructor(val keyId: String, val kind: DecisionKind?, val probabilities: List<RawProbability>, val selectedId: String?, val pTrue: Double?, val keyFailure: KeyFailure?, val safeCode: DecisionSafeCode?) { companion object { @JvmStatic fun yesNo(keyId: String, pTrue: Double, selectedId: String?) = RawAnswer(keyId, DecisionKind.YES_NO, emptyList(), selectedId, pTrue, null, null); @JvmStatic fun distribution(keyId: String, kind: DecisionKind, probabilities: List<RawProbability>, selectedId: String?) = RawAnswer(keyId, kind, Collections.unmodifiableList(ArrayList(probabilities)), selectedId, null, null, null); @JvmStatic fun failure(keyId: String, keyFailure: KeyFailure, safeCode: DecisionSafeCode) = RawAnswer(keyId, null, emptyList(), null, null, keyFailure, safeCode) } }
@ApiStatus.Experimental class RawDecisionOutcome private constructor(val answers: List<RawAnswer>?, val provenance: DecisionProvenance?, val callFailure: CallFailure?, val safeCode: DecisionSafeCode?) { companion object { @JvmStatic fun success(answers: List<RawAnswer>, provenance: DecisionProvenance) = RawDecisionOutcome(Collections.unmodifiableList(ArrayList(answers)), provenance, null, null); @JvmStatic fun failure(callFailure: CallFailure, safeCode: DecisionSafeCode) = RawDecisionOutcome(null, null, callFailure, safeCode) } }
@ApiStatus.Experimental fun interface DecisionProvider { fun invoke(request: PreparedDecisionRequest): RawDecisionOutcome }

@ApiStatus.Experimental interface DecisionRecord { val mode: RecordMode; val fields: Map<String, String> }
private data class SafeRecord(override val mode: RecordMode, override val fields: Map<String, String>) : DecisionRecord

@ApiStatus.Experimental
class DecisionModel(private val provider: DecisionProvider) {
    private var defaultTimeout = Duration.ofSeconds(30)
    private var defaultPolicy = DecisionRecordPolicy.metadata()
    private var clock: () -> Long = System::nanoTime
    fun withDefaults(defaultTimeout: Duration, defaultRecordPolicy: DecisionRecordPolicy): DecisionModel {
        validDuration(defaultTimeout)
        return DecisionModel(provider).also { it.defaultTimeout = defaultTimeout; it.defaultPolicy = defaultRecordPolicy; it.clock = clock }
    }
    fun ask(request: DecisionRequest): DecisionOutcome {
        val prepared = try { prepare(request, defaultTimeout, defaultPolicy, clock(), clock) } catch (_: RuntimeException) { return failed(CallFailure.RejectedRequest, null, null) }
        val raw = try { invokeWithinDeadline(prepared) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return failed(CallFailure.Cancelled, DecisionSafeCode.CANCELLED, prepared) } ?: return failed(CallFailure.DeadlineExceeded, DecisionSafeCode.DEADLINE_EXCEEDED, prepared)
        if (remaining(prepared) == 0L) return failed(CallFailure.DeadlineExceeded, DecisionSafeCode.DEADLINE_EXCEEDED, prepared)
        raw.callFailure?.let { return failed(it, code(it), prepared) }; val input = raw.answers ?: return failed(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST, prepared); val source = raw.provenance ?: return failed(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST, prepared)
        if (input.map { it.keyId }.distinct().size != input.size || input.any { answer -> prepared.questions.none { it.id == answer.keyId } }) return failed(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST, prepared)
        val data = requestData(request); val answers = data.questions.associate { question -> question.key to validate(question, input.firstOrNull { it.keyId == question.id }) }; val provenance = completed(source, prepared)
        return DecisionOutcome.Success(provenance, safeRecord(prepared.recordPolicy, provenance, answers), Collections.unmodifiableMap(answers))
    }
    private fun invokeWithinDeadline(prepared: PreparedDecisionRequest): RawDecisionOutcome? {
        val future = try { DecisionExecutionSupport.submit(Callable { provider.invoke(prepared) }) } catch (_: RejectedExecutionException) { return RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE) }
        return try { future.get(remaining(prepared), TimeUnit.NANOSECONDS) } catch (_: TimeoutException) { future.cancel(true); null } catch (_: InterruptedException) { future.cancel(true); throw InterruptedException() } catch (_: java.util.concurrent.ExecutionException) { RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE) }
    }
    private fun validate(question: QuestionData<*>, raw: RawAnswer?): KeyOutcome<*> {
        if (raw == null) return KeyOutcome.Failure<Any?>(KeyFailure.Missing, DecisionSafeCode.MISSING); raw.keyFailure?.let { return KeyOutcome.Failure<Any?>(it, code(it)) }; if (raw.kind != question.kind) return KeyOutcome.Failure<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        if (question.kind == DecisionKind.YES_NO) return yesNo(raw); val expected = question.options.map { it.id }
        if (raw.probabilities.map { it.supportId }.distinct().size != raw.probabilities.size || raw.probabilities.map { it.supportId }.toSet() != expected.toSet()) return KeyOutcome.Failure<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        val probabilities = raw.probabilities.associate { it.supportId to it.probability }; if (probabilities.values.any { !it.isFinite() || it !in 0.0..1.0 } || kotlin.math.abs(probabilities.values.sum() - 1.0) > 1e-9) return KeyOutcome.Failure<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        val maximum = probabilities.values.max(); val ids = expected.filter { probabilities.getValue(it) == maximum }; if (raw.selectedId != null && raw.selectedId !in ids) return KeyOutcome.Failure<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        @Suppress("UNCHECKED_CAST") val options = question.options as List<DecisionOption<Any?>>; val values = linkedMapOf<Any?, Double>(); options.forEach { values[it.value] = probabilities.getValue(it.id) }; val maxima = options.filter { it.id in ids }.map { it.value }; val selected = options.first { it.id == (raw.selectedId ?: ids.first()) }.value; val score = if (question.kind == DecisionKind.RATING) options.withIndex().sumOf { it.index * probabilities.getValue(it.value.id) } else null
        return KeyOutcome.Success(selected, Collections.unmodifiableMap(values), immutableList(maxima), maxima.first(), score, raw.selectedId ?: ids.first())
    }
    private fun yesNo(raw: RawAnswer): KeyOutcome<Boolean> { val p = raw.pTrue ?: return KeyOutcome.Failure(KeyFailure.Invalid, DecisionSafeCode.INVALID); if (!p.isFinite() || p !in 0.0..1.0) return KeyOutcome.Failure(KeyFailure.Invalid, DecisionSafeCode.INVALID); val values = linkedMapOf(false to 1.0 - p, true to p); val maxima = values.filterValues { it == values.values.max() }.keys.toList(); if (raw.selectedId != null && raw.selectedId !in maxima.map { it.toString() }) return KeyOutcome.Failure(KeyFailure.Invalid, DecisionSafeCode.INVALID); val selected = raw.selectedId?.toBooleanStrictOrNull() ?: maxima.first(); return KeyOutcome.Success(selected, Collections.unmodifiableMap(values), immutableList(maxima), maxima.first(), null, raw.selectedId ?: maxima.first().toString()) }
    private fun failed(failure: CallFailure, safeCode: DecisionSafeCode?, request: PreparedDecisionRequest?): DecisionOutcome { val canonical = safeCode ?: code(failure); val provenance = facadeProvenance(request); return DecisionOutcome.Failure(failure, canonical, provenance, request?.let { safeRecord(it.recordPolicy, provenance, emptyMap()) }) }
    private fun requestData(request: DecisionRequest) = (request as? RequestToken)?.data ?: throw IllegalArgumentException("unknown decision request")
    private fun prepare(request: DecisionRequest, timeoutDefault: Duration, policyDefault: DecisionRecordPolicy, start: Long, clock: () -> Long): PreparedDecisionRequest { val data = requestData(request); val timeout = data.timeout ?: timeoutDefault; validDuration(timeout); val nanos = try { timeout.toNanos() } catch (_: ArithmeticException) { throw IllegalArgumentException("timeout too large") }; val deadline = if (start >= 0 && start > Long.MAX_VALUE - nanos) Long.MAX_VALUE else if (start < 0 && start < Long.MIN_VALUE + nanos) Long.MAX_VALUE else start + nanos; val questions = data.questions.map { question -> val support = if (question.kind == DecisionKind.YES_NO) listOf(PreparedSupportData("false", "false"), PreparedSupportData("true", "true")) else question.options.map { PreparedSupportData(it.id, it.label) }; PreparedQuestionData(question.id, question.kind, question.text, immutableList(support)) }; return PreparedData(data.state, immutableList(questions), deadline, data.policy ?: policyDefault, data.correlationId, UUID.randomUUID().toString(), fingerprint(questions), clock) }
    private fun remaining(request: PreparedDecisionRequest): Long = request.remainingNanos()
    private fun safeRecord(policy: DecisionRecordPolicy, provenance: DecisionProvenance, answers: Map<DecisionKey<*>, KeyOutcome<*>>): DecisionRecord? { if (policy.mode == RecordMode.NONE) return null; val max = policy.maxBytes ?: MAX_METADATA_BYTES; val fields = linkedMapOf("envelopeVersion" to "1", "schemaVersion" to "1", "provider" to provenance.provider, "requestId" to (provenance.requestId ?: ""), "questionFingerprint" to (provenance.questionFingerprint ?: ""), "questionCount" to answers.size.toString()); provenance.correlationId?.let { fields["correlationId"] = it }; val failures = answers.values.filterIsInstance<KeyOutcome.Failure<*>>().map { it.safeCode.name }; if (failures.isNotEmpty()) fields["safeCodes"] = failures.joinToString(","); if (policy.mode == RecordMode.FULL && "answerIds" in policy.allowlist) fields["answerIds"] = answers.values.filterIsInstance<KeyOutcome.Success<*>>().joinToString(",") { it.selectedSupportId() }; val bounded = linkedMapOf<String, String>(); var bytes = 0; for ((key, value) in fields) { val size = (key + value).toByteArray(StandardCharsets.UTF_8).size; if (bytes + size <= max) { bounded[key] = value; bytes += size } }; return SafeRecord(policy.mode, Collections.unmodifiableMap(bounded)) }
    private fun facadeProvenance(request: PreparedDecisionRequest?) = DecisionProvenance.builder("decision-facade", EvidenceKind.VERBALIZED).requestId(request?.requestId ?: UUID.randomUUID().toString()).apply { request?.correlationId?.let { correlationId(it) }; request?.questionFingerprint?.let { questionFingerprint(it) } }.build()
    private fun completed(source: DecisionProvenance, request: PreparedDecisionRequest) = DecisionProvenance.builder(source.provider, source.evidenceKind).apply { source.requestedModel?.let { requestedModel(it) }; source.resolvedModel?.let { resolvedModel(it) }; source.modelVersion?.let { modelVersion(it) }; source.adapterVersion?.let { adapterVersion(it) }; source.promptVersion?.let { promptVersion(it) }; requestId(request.requestId); request.correlationId?.let { correlationId(it) }; questionFingerprint(request.questionFingerprint); source.timestamp?.let { timestamp(it) }; source.usage?.let { usage(it.inputTokens ?: 0, it.outputTokens ?: 0) } }.build()
    private fun validDuration(value: Duration) { require(!value.isNegative && !value.isZero) { "timeout must be positive" }; try { value.toNanos() } catch (_: ArithmeticException) { throw IllegalArgumentException("timeout too large") } }
    private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    private fun code(failure: CallFailure) = when (failure) { CallFailure.Disabled -> DecisionSafeCode.DISABLED; CallFailure.Unavailable -> DecisionSafeCode.UNAVAILABLE; CallFailure.RejectedRequest -> DecisionSafeCode.REJECTED_REQUEST; CallFailure.DeadlineExceeded -> DecisionSafeCode.DEADLINE_EXCEEDED; CallFailure.Cancelled -> DecisionSafeCode.CANCELLED; CallFailure.Unsupported -> DecisionSafeCode.UNSUPPORTED }
    private fun code(failure: KeyFailure) = when (failure) { KeyFailure.Missing -> DecisionSafeCode.MISSING; KeyFailure.Invalid -> DecisionSafeCode.INVALID; KeyFailure.Unsupported -> DecisionSafeCode.UNSUPPORTED }
    private fun fingerprint(questions: List<PreparedQuestionData>): String = MessageDigest.getInstance("SHA-256").digest(questions.joinToString("") { question -> listOf(question.kind.name, question.id, *question.support.map { it.id }.toTypedArray()).joinToString("") { value -> "${value.toByteArray(StandardCharsets.UTF_8).size}:$value|" } }.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

@ApiStatus.Experimental object NoDecisionModel { @JvmStatic fun create() = DecisionModel(DecisionProvider { RawDecisionOutcome.failure(CallFailure.Disabled, DecisionSafeCode.DISABLED) }) }
@ApiStatus.Experimental interface StubStep { companion object { @JvmStatic fun immediate(raw: RawDecisionOutcome): StubStep = StubStepData(Duration.ZERO, raw); @JvmStatic fun after(delay: Duration, raw: RawDecisionOutcome): StubStep { require(!delay.isNegative); return StubStepData(delay, raw) } } }
private data class StubStepData(val delay: Duration, val raw: RawDecisionOutcome) : StubStep
@ApiStatus.Experimental object StubDecisionModel { @JvmStatic fun create(steps: List<StubStep>): DecisionModel { require(steps.isNotEmpty()); val scripted = steps.map { step -> step as? StubStepData ?: throw IllegalArgumentException("foreign stub step") }; val cursor = java.util.concurrent.atomic.AtomicInteger(); return DecisionModel(DecisionProvider { request -> scripted.getOrNull(cursor.getAndIncrement())?.let { step -> if (step.delay.toNanos() >= request.remainingNanos()) RawDecisionOutcome.failure(CallFailure.DeadlineExceeded, DecisionSafeCode.DEADLINE_EXCEEDED) else { if (!step.delay.isZero) Thread.sleep(step.delay.toMillis()); step.raw } } ?: RawDecisionOutcome.failure(CallFailure.Unsupported, DecisionSafeCode.UNSUPPORTED) }) } }

private data class RequestData(val binding: String, val state: Map<String, Any?>, val questions: List<QuestionData<*>>, val timeout: Duration?, val policy: DecisionRecordPolicy?, val correlationId: String?)
private data class QuestionData<T>(val key: DecisionKey<T>, val kind: DecisionKind, val options: List<DecisionOption<T>>) { val id get() = key.id; val text get() = when (key) { is YesNoToken -> key.question; is ChoiceToken<*> -> key.question; is RatingToken<*> -> key.question; else -> error("foreign key") } }
private const val MAX_RECORD_BYTES = 1_048_576
private const val MAX_METADATA_BYTES = 4_096
