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
import java.util.WeakHashMap
import java.util.concurrent.Callable
import java.util.concurrent.Executors
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
    init { opaque(id, "support id"); require(label.isNotBlank()) { "label must not be blank" } }
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
        fun state(values: Map<String, Any?>) = apply { state = safeMap(values) }
        fun correlationId(correlationId: String) = apply { correlation(correlationId); this.correlationId = correlationId }
        fun timeout(timeout: Duration) = apply { validDuration(timeout); this.timeout = timeout }
        fun recordPolicy(policy: DecisionRecordPolicy) = apply { this.policy = policy }
        fun yesNo(id: String, question: String): YesNoKey { question(id, question); return YesNoToken(id, binding, question).also { questions += QuestionData(it, DecisionKind.YES_NO, emptyList()) } }
        fun <T> choice(id: String, question: String, options: List<DecisionOption<T>>): ChoiceKey<T> { question(id, question); options(options); return ChoiceToken(id, binding, question, immutableList(options)).also { questions += QuestionData(it, DecisionKind.CHOICE, it.options) } }
        fun <T> rating(id: String, question: String, levels: List<DecisionOption<T>>): RatingKey<T> { question(id, question); options(levels); return RatingToken(id, binding, question, immutableList(levels)).also { questions += QuestionData(it, DecisionKind.RATING, it.options) } }
        fun build(): DecisionRequest { require(questions.isNotEmpty()) { "a decision request needs at least one question" }; return RequestToken().also { requestStore[it] = RequestData(binding, state, immutableList(questions), timeout, policy, correlationId) } }
        private fun question(id: String, question: String) { opaque(id, "key id"); require(question.isNotBlank()) { "question must not be blank" }; require(questions.none { it.id == id }) { "duplicate key id" } }
        private fun <T> options(options: List<DecisionOption<T>>) { require(options.isNotEmpty()) { "support must not be empty" }; require(options.map { it.id }.distinct().size == options.size) { "duplicate support id" }; require(options.map { it.value }.distinct().size == options.size) { "support values must be distinct" } }
    }
    companion object { @JvmStatic fun builder() = Builder() }
}
private class RequestToken : DecisionRequest

@ApiStatus.Experimental interface PreparedDecisionRequest { val state: Map<String, Any?>; val questions: List<PreparedQuestion>; val deadlineNanos: Long; val recordPolicy: DecisionRecordPolicy; val correlationId: String?; val requestId: String; val questionFingerprint: String; fun remainingNanos(): Long }
@ApiStatus.Experimental interface PreparedQuestion { val id: String; val kind: DecisionKind; val question: String; val support: List<PreparedSupport> }
@ApiStatus.Experimental interface PreparedSupport { val id: String; val label: String }
private data class PreparedSupportData(override val id: String, override val label: String) : PreparedSupport
private data class PreparedQuestionData(override val id: String, override val kind: DecisionKind, override val question: String, override val support: List<PreparedSupport>) : PreparedQuestion
private data class PreparedData(override val state: Map<String, Any?>, override val questions: List<PreparedQuestion>, override val deadlineNanos: Long, override val recordPolicy: DecisionRecordPolicy, override val correlationId: String?, override val requestId: String, override val questionFingerprint: String, private val clock: () -> Long) : PreparedDecisionRequest { override fun remainingNanos(): Long = (deadlineNanos - clock()).coerceAtLeast(0) }

@ApiStatus.Experimental interface DecisionUsage { val inputTokens: Int?; val outputTokens: Int? }
private data class UsageData(override val inputTokens: Int?, override val outputTokens: Int?) : DecisionUsage
@ApiStatus.Experimental
class DecisionProvenance private constructor(val provider: String, val evidenceKind: EvidenceKind, val requestedModel: String?, val resolvedModel: String?, val modelVersion: String?, val adapterVersion: String?, val promptVersion: String?, val requestId: String?, val correlationId: String?, val questionFingerprint: String?, val timestamp: Instant?, val usage: DecisionUsage?) {
    @ApiStatus.Experimental interface Builder {
        fun requestedModel(value: String): Builder; fun resolvedModel(value: String): Builder; fun modelVersion(value: String): Builder; fun adapterVersion(value: String): Builder; fun promptVersion(value: String): Builder; fun requestId(value: String): Builder; fun correlationId(value: String): Builder; fun questionFingerprint(value: String): Builder; fun timestamp(value: Instant): Builder; fun usage(inputTokens: Int, outputTokens: Int): Builder; fun build(): DecisionProvenance
    }
    private class BuilderData(private val provider: String, private val evidenceKind: EvidenceKind) : Builder {
        private var requestedModel: String? = null; private var resolvedModel: String? = null; private var modelVersion: String? = null; private var adapterVersion: String? = null; private var promptVersion: String? = null; private var requestId: String? = null; private var correlationId: String? = null; private var fingerprint: String? = null; private var timestamp: Instant? = null; private var usage: DecisionUsage? = null
        override fun requestedModel(value: String) = apply { requestedModel = safeText(value) }; override fun resolvedModel(value: String) = apply { resolvedModel = safeText(value) }; override fun modelVersion(value: String) = apply { modelVersion = safeText(value) }; override fun adapterVersion(value: String) = apply { adapterVersion = safeText(value) }; override fun promptVersion(value: String) = apply { promptVersion = safeText(value) }; override fun requestId(value: String) = apply { requestId = safeText(value) }; override fun correlationId(value: String) = apply { correlation(value); correlationId = value }; override fun questionFingerprint(value: String) = apply { fingerprint = safeText(value) }; override fun timestamp(value: Instant) = apply { timestamp = value }; override fun usage(inputTokens: Int, outputTokens: Int) = apply { require(inputTokens >= 0 && outputTokens >= 0); usage = UsageData(inputTokens, outputTokens) }
        override fun build() = DecisionProvenance(provider, evidenceKind, requestedModel, resolvedModel, modelVersion, adapterVersion, promptVersion, requestId, correlationId, fingerprint, timestamp, usage)
    }
    companion object { @JvmStatic fun builder(provider: String, evidenceKind: EvidenceKind): Builder { safeText(provider); return BuilderData(provider, evidenceKind) } }
}

@ApiStatus.Experimental class RawProbability private constructor(val supportId: String, val probability: Double) { companion object { @JvmStatic fun of(supportId: String, probability: Double) = RawProbability(supportId, probability) } }
@ApiStatus.Experimental class RawAnswer private constructor(val keyId: String, val kind: DecisionKind?, val probabilities: List<RawProbability>, val selectedId: String?, val pTrue: Double?, val keyFailure: KeyFailure?, val safeCode: DecisionSafeCode?) { companion object { @JvmStatic fun yesNo(keyId: String, pTrue: Double, selectedId: String?) = RawAnswer(keyId, DecisionKind.YES_NO, emptyList(), selectedId, pTrue, null, null); @JvmStatic fun distribution(keyId: String, kind: DecisionKind, probabilities: List<RawProbability>, selectedId: String?) = RawAnswer(keyId, kind, immutableList(probabilities), selectedId, null, null, null); @JvmStatic fun failure(keyId: String, keyFailure: KeyFailure, safeCode: DecisionSafeCode) = RawAnswer(keyId, null, emptyList(), null, null, keyFailure, safeCode) } }
@ApiStatus.Experimental class RawDecisionOutcome private constructor(val answers: List<RawAnswer>?, val provenance: DecisionProvenance?, val callFailure: CallFailure?, val safeCode: DecisionSafeCode?) { companion object { @JvmStatic fun success(answers: List<RawAnswer>, provenance: DecisionProvenance) = RawDecisionOutcome(immutableList(answers), provenance, null, null); @JvmStatic fun failure(callFailure: CallFailure, safeCode: DecisionSafeCode) = RawDecisionOutcome(null, null, callFailure, safeCode) } }
@ApiStatus.Experimental fun interface DecisionProvider { fun invoke(request: PreparedDecisionRequest): RawDecisionOutcome }

@ApiStatus.Experimental interface DecisionRecord { val mode: RecordMode; val fields: Map<String, String> }
private data class SafeRecord(override val mode: RecordMode, override val fields: Map<String, String>) : DecisionRecord
@ApiStatus.Experimental sealed interface KeyOutcome<T> { @ApiStatus.Experimental interface Success<T> : KeyOutcome<T> { val value: T; val distribution: Map<T, Double>; val maximizers: List<T>; val firstMaximizer: T; val expectedScore: Double? }; @ApiStatus.Experimental interface Failure<T> : KeyOutcome<T> { val failure: KeyFailure; val safeCode: DecisionSafeCode } }
private data class KeySuccess<T>(override val value: T, override val distribution: Map<T, Double>, override val maximizers: List<T>, override val firstMaximizer: T, override val expectedScore: Double?) : KeyOutcome.Success<T>
private data class KeyFailureData<T>(override val failure: KeyFailure, override val safeCode: DecisionSafeCode) : KeyOutcome.Failure<T>
@ApiStatus.Experimental sealed interface DecisionOutcome { @ApiStatus.Experimental interface Success : DecisionOutcome { val provenance: DecisionProvenance; val record: DecisionRecord?; fun <T> answer(key: DecisionKey<T>): KeyOutcome<T> }; @ApiStatus.Experimental interface Failure : DecisionOutcome { val failure: CallFailure; val safeCode: DecisionSafeCode; val provenance: DecisionProvenance; val record: DecisionRecord? } }
private data class DecisionSuccess(override val provenance: DecisionProvenance, override val record: DecisionRecord?, private val binding: String, private val answers: Map<String, KeyOutcome<*>>) : DecisionOutcome.Success { override fun <T> answer(key: DecisionKey<T>): KeyOutcome<T> { require(tokenBinding(key) == binding) { "decision key belongs to another request" }; @Suppress("UNCHECKED_CAST") return answers[key.id] as? KeyOutcome<T> ?: KeyFailureData(KeyFailure.Missing, DecisionSafeCode.MISSING) } }
private data class DecisionFailure(override val failure: CallFailure, override val safeCode: DecisionSafeCode, override val provenance: DecisionProvenance, override val record: DecisionRecord?) : DecisionOutcome.Failure

@ApiStatus.Experimental
class DecisionModel(private val provider: DecisionProvider) {
    init { modelConfigs[this] = ModelConfig(Duration.ofSeconds(30), DecisionRecordPolicy.metadata(), System::nanoTime) }
    fun withDefaults(defaultTimeout: Duration, defaultRecordPolicy: DecisionRecordPolicy): DecisionModel {
        validDuration(defaultTimeout)
        val inherited = config()
        return DecisionModel(provider).also { modelConfigs[it] = ModelConfig(defaultTimeout, defaultRecordPolicy, inherited.clock) }
    }
    fun ask(request: DecisionRequest): DecisionOutcome {
        val config = config()
        val prepared = try { prepare(request, config.timeout, config.policy, config.clock(), config.clock) } catch (_: RuntimeException) { return failed(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST, null) }
        val raw = try { invokeWithinDeadline(prepared) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return failed(CallFailure.Cancelled, DecisionSafeCode.CANCELLED, prepared) } ?: return failed(CallFailure.DeadlineExceeded, DecisionSafeCode.DEADLINE_EXCEEDED, prepared)
        if (remaining(prepared) == 0L) return failed(CallFailure.DeadlineExceeded, DecisionSafeCode.DEADLINE_EXCEEDED, prepared)
        raw.callFailure?.let { return failed(it, raw.safeCode ?: code(it), prepared) }; val input = raw.answers ?: return failed(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST, prepared); val source = raw.provenance ?: return failed(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST, prepared)
        if (input.map { it.keyId }.distinct().size != input.size || input.any { answer -> prepared.questions.none { it.id == answer.keyId } }) return failed(CallFailure.RejectedRequest, DecisionSafeCode.REJECTED_REQUEST, prepared)
        val data = requestData(request); val answers = data.questions.associate { question -> question.id to validate(question, input.firstOrNull { it.keyId == question.id }) }; val provenance = completed(source, prepared)
        return DecisionSuccess(provenance, safeRecord(prepared.recordPolicy, provenance, answers), data.binding, Collections.unmodifiableMap(answers))
    }
    private fun invokeWithinDeadline(prepared: PreparedDecisionRequest): RawDecisionOutcome? { val future = workers.submit(Callable { provider.invoke(prepared) }); return try { future.get(remaining(prepared), TimeUnit.NANOSECONDS) } catch (_: TimeoutException) { future.cancel(true); null } catch (_: java.util.concurrent.ExecutionException) { RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE) } }
    private fun validate(question: QuestionData<*>, raw: RawAnswer?): KeyOutcome<*> {
        if (raw == null) return KeyFailureData<Any?>(KeyFailure.Missing, DecisionSafeCode.MISSING); raw.keyFailure?.let { return KeyFailureData<Any?>(it, raw.safeCode ?: code(it)) }; if (raw.kind != question.kind) return KeyFailureData<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        if (question.kind == DecisionKind.YES_NO) return yesNo(raw); val expected = question.options.map { it.id }
        if (raw.probabilities.map { it.supportId }.distinct().size != raw.probabilities.size || raw.probabilities.map { it.supportId }.toSet() != expected.toSet()) return KeyFailureData<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        val probabilities = raw.probabilities.associate { it.supportId to it.probability }; if (probabilities.values.any { !it.isFinite() || it !in 0.0..1.0 } || kotlin.math.abs(probabilities.values.sum() - 1.0) > 1e-9) return KeyFailureData<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        val maximum = probabilities.values.max(); val ids = expected.filter { probabilities.getValue(it) == maximum }; if (raw.selectedId != null && raw.selectedId !in ids) return KeyFailureData<Any?>(KeyFailure.Invalid, DecisionSafeCode.INVALID)
        @Suppress("UNCHECKED_CAST") val options = question.options as List<DecisionOption<Any?>>; val values = linkedMapOf<Any?, Double>(); options.forEach { values[it.value] = probabilities.getValue(it.id) }; val maxima = options.filter { it.id in ids }.map { it.value }; val selected = options.first { it.id == (raw.selectedId ?: ids.first()) }.value; val score = if (question.kind == DecisionKind.RATING) options.withIndex().sumOf { it.index * probabilities.getValue(it.value.id) } else null
        return KeySuccess(selected, Collections.unmodifiableMap(values), immutableList(maxima), maxima.first(), score)
    }
    private fun yesNo(raw: RawAnswer): KeyOutcome<Boolean> { val p = raw.pTrue ?: return KeyFailureData(KeyFailure.Invalid, DecisionSafeCode.INVALID); if (!p.isFinite() || p !in 0.0..1.0) return KeyFailureData(KeyFailure.Invalid, DecisionSafeCode.INVALID); val values = linkedMapOf(false to 1.0 - p, true to p); val maxima = values.filterValues { it == values.values.max() }.keys.toList(); if (raw.selectedId != null && raw.selectedId !in maxima.map { it.toString() }) return KeyFailureData(KeyFailure.Invalid, DecisionSafeCode.INVALID); val selected = raw.selectedId?.toBooleanStrictOrNull() ?: maxima.first(); return KeySuccess(selected, Collections.unmodifiableMap(values), immutableList(maxima), maxima.first(), null) }
    private fun failed(failure: CallFailure, safeCode: DecisionSafeCode, request: PreparedDecisionRequest?): DecisionOutcome { val provenance = facadeProvenance(request); return DecisionFailure(failure, safeCode, provenance, request?.let { safeRecord(it.recordPolicy, provenance, emptyMap()) }) }
    private fun config() = modelConfigs[this] ?: error("decision model configuration unavailable")
}
private data class ModelConfig(val timeout: Duration, val policy: DecisionRecordPolicy, val clock: () -> Long)
private val modelConfigs = Collections.synchronizedMap(WeakHashMap<DecisionModel, ModelConfig>())
@JvmSynthetic internal fun decisionModelForTesting(provider: DecisionProvider, timeout: Duration, policy: DecisionRecordPolicy, clock: () -> Long): DecisionModel { validDuration(timeout); return DecisionModel(provider).also { modelConfigs[it] = ModelConfig(timeout, policy, clock) } }

@ApiStatus.Experimental object NoDecisionModel { @JvmStatic fun create() = DecisionModel(DecisionProvider { RawDecisionOutcome.failure(CallFailure.Disabled, DecisionSafeCode.DISABLED) }) }
@ApiStatus.Experimental interface StubStep { companion object { @JvmStatic fun immediate(raw: RawDecisionOutcome): StubStep = StubStepData(Duration.ZERO, raw); @JvmStatic fun after(delay: Duration, raw: RawDecisionOutcome): StubStep { require(!delay.isNegative); return StubStepData(delay, raw) } } }
private data class StubStepData(val delay: Duration, val raw: RawDecisionOutcome) : StubStep
@ApiStatus.Experimental object StubDecisionModel { @JvmStatic fun create(steps: List<StubStep>): DecisionModel { require(steps.isNotEmpty()); val scripted = steps.map { step -> step as? StubStepData ?: throw IllegalArgumentException("foreign stub step") }; val cursor = java.util.concurrent.atomic.AtomicInteger(); return DecisionModel(DecisionProvider { request -> scripted.getOrNull(cursor.getAndIncrement())?.let { step -> if (step.delay.toNanos() >= remaining(request)) RawDecisionOutcome.failure(CallFailure.DeadlineExceeded, DecisionSafeCode.DEADLINE_EXCEEDED) else { if (!step.delay.isZero) Thread.sleep(step.delay.toMillis()); step.raw } } ?: RawDecisionOutcome.failure(CallFailure.Unsupported, DecisionSafeCode.UNSUPPORTED) }) } }

private data class RequestData(val binding: String, val state: Map<String, Any?>, val questions: List<QuestionData<*>>, val timeout: Duration?, val policy: DecisionRecordPolicy?, val correlationId: String?)
private data class QuestionData<T>(val key: DecisionKey<T>, val kind: DecisionKind, val options: List<DecisionOption<T>>) { val id get() = key.id; val text get() = when (key) { is YesNoToken -> key.question; is ChoiceToken<*> -> key.question; is RatingToken<*> -> key.question; else -> error("foreign key") } }
private val requestStore = Collections.synchronizedMap(WeakHashMap<DecisionRequest, RequestData>())
private val workers = Executors.newCachedThreadPool { runnable -> Thread(runnable, "embabel-decision").apply { isDaemon = true } }
private fun requestData(request: DecisionRequest) = requestStore[request] ?: throw IllegalArgumentException("unknown decision request")
private fun prepare(request: DecisionRequest, timeoutDefault: Duration, policyDefault: DecisionRecordPolicy, start: Long, clock: () -> Long): PreparedDecisionRequest { val data = requestData(request); val timeout = data.timeout ?: timeoutDefault; validDuration(timeout); val nanos = try { timeout.toNanos() } catch (_: ArithmeticException) { throw IllegalArgumentException("timeout too large") }; val deadline = if (Long.MAX_VALUE - start < nanos) Long.MAX_VALUE else start + nanos; val questions = data.questions.map { question -> val support = if (question.kind == DecisionKind.YES_NO) listOf(PreparedSupportData("false", "false"), PreparedSupportData("true", "true")) else question.options.map { PreparedSupportData(it.id, it.label) }; PreparedQuestionData(question.id, question.kind, question.text, immutableList(support)) }; val fingerprint = fingerprint(questions); return PreparedData(data.state, immutableList(questions), deadline, data.policy ?: policyDefault, data.correlationId, UUID.randomUUID().toString(), fingerprint, clock) }
private fun remaining(request: PreparedDecisionRequest): Long = request.remainingNanos()
private fun safeRecord(policy: DecisionRecordPolicy, provenance: DecisionProvenance, answers: Map<String, KeyOutcome<*>>): DecisionRecord? { if (policy.mode == RecordMode.NONE) return null; val max = policy.maxBytes ?: MAX_METADATA_BYTES; val fields = linkedMapOf("envelopeVersion" to "1", "provider" to provenance.provider, "requestId" to (provenance.requestId ?: ""), "questionFingerprint" to (provenance.questionFingerprint ?: ""), "questionCount" to answers.size.toString()); provenance.correlationId?.let { fields["correlationId"] = it }; val failures = answers.values.filterIsInstance<KeyOutcome.Failure<*>>().map { it.safeCode.name }; if (failures.isNotEmpty()) fields["safeCodes"] = failures.joinToString(","); if (policy.mode == RecordMode.FULL && "answerIds" in policy.allowlist) fields["answerIds"] = answers.keys.joinToString(","); val bounded = linkedMapOf<String, String>(); var bytes = 0; for ((key, value) in fields) { val size = (key + value).toByteArray(StandardCharsets.UTF_8).size; if (bytes + size <= max) { bounded[key] = value; bytes += size } }; return SafeRecord(policy.mode, Collections.unmodifiableMap(bounded)) }
private fun facadeProvenance(request: PreparedDecisionRequest?) = DecisionProvenance.builder("decision-facade", EvidenceKind.VERBALIZED).requestId(request?.requestId ?: UUID.randomUUID().toString()).apply { request?.correlationId?.let { correlationId(it) }; request?.questionFingerprint?.let { questionFingerprint(it) } }.build()
private fun completed(source: DecisionProvenance, request: PreparedDecisionRequest) = DecisionProvenance.builder(source.provider, source.evidenceKind).apply { source.requestedModel?.let { requestedModel(it) }; source.resolvedModel?.let { resolvedModel(it) }; source.modelVersion?.let { modelVersion(it) }; source.adapterVersion?.let { adapterVersion(it) }; source.promptVersion?.let { promptVersion(it) }; requestId(request.requestId); request.correlationId?.let { correlationId(it) }; questionFingerprint(request.questionFingerprint); source.timestamp?.let { timestamp(it) }; source.usage?.let { usage(it.inputTokens ?: 0, it.outputTokens ?: 0) } }.build()
private fun opaque(value: String, label: String) { require(value.isNotBlank() && value.length <= 256 && value.none { it.isWhitespace() }) { "$label must be a nonblank opaque token" } }
private fun correlation(value: String) { require(value.isNotBlank() && value.toByteArray(StandardCharsets.UTF_8).size <= 256) { "correlationId must be nonblank and at most 256 UTF-8 bytes" } }
private fun safeText(value: String): String { require(value.isNotBlank() && value.toByteArray(StandardCharsets.UTF_8).size <= 256 && value.none { it == '\n' || it == '\r' }) { "provenance values must be bounded safe text" }; return value }
private fun validDuration(value: Duration) { require(!value.isNegative && !value.isZero) { "timeout must be positive" }; try { value.toNanos() } catch (_: ArithmeticException) { throw IllegalArgumentException("timeout too large") } }
private fun safeMap(values: Map<String, Any?>): Map<String, Any?> = Collections.unmodifiableMap(LinkedHashMap<String, Any?>().apply { values.forEach { (key, value) -> if (!sensitive(key)) put(key, safeValue(value)) } })
private fun safeValue(value: Any?): Any? = when (value) { null, is String, is Number, is Boolean -> value; is Map<*, *> -> safeMap(value.entries.filter { it.key is String }.associate { it.key as String to it.value }); is Iterable<*> -> immutableList(value.map { safeValue(it) }); is Array<*> -> immutableList(value.map { safeValue(it) }); else -> throw IllegalArgumentException("state values must be scalar, map, or collection") }
private fun sensitive(key: String) = listOf("secret", "password", "token", "credential", "apikey", "api_key").any { key.contains(it, true) }
private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
private fun code(failure: CallFailure) = when (failure) { CallFailure.Disabled -> DecisionSafeCode.DISABLED; CallFailure.Unavailable -> DecisionSafeCode.UNAVAILABLE; CallFailure.RejectedRequest -> DecisionSafeCode.REJECTED_REQUEST; CallFailure.DeadlineExceeded -> DecisionSafeCode.DEADLINE_EXCEEDED; CallFailure.Cancelled -> DecisionSafeCode.CANCELLED; CallFailure.Unsupported -> DecisionSafeCode.UNSUPPORTED }
private fun code(failure: KeyFailure) = when (failure) { KeyFailure.Missing -> DecisionSafeCode.MISSING; KeyFailure.Invalid -> DecisionSafeCode.INVALID; KeyFailure.Unsupported -> DecisionSafeCode.UNSUPPORTED }
private fun tokenBinding(key: DecisionKey<*>) = when (key) { is YesNoToken -> key.binding; is ChoiceToken<*> -> key.binding; is RatingToken<*> -> key.binding; else -> throw IllegalArgumentException("foreign decision key") }
private fun fingerprint(questions: List<PreparedQuestionData>): String = MessageDigest.getInstance("SHA-256").digest(questions.joinToString("") { question -> listOf(question.kind.name, question.id, *question.support.map { it.id }.toTypedArray()).joinToString("") { value -> "${value.toByteArray(StandardCharsets.UTF_8).size}:$value|" } }.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
private const val MAX_RECORD_BYTES = 1_048_576
private const val MAX_METADATA_BYTES = 4_096
