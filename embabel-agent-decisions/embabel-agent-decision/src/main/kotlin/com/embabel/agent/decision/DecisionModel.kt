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
import java.security.MessageDigest
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private class YesNoToken(override val id: String, val binding: String, val question: String) : YesNoKey
private class ChoiceToken<T>(override val id: String, val binding: String, val question: String, val options: List<DecisionOption<T>>) : ChoiceKey<T>
private class RatingToken<T>(override val id: String, val binding: String, val question: String, val options: List<DecisionOption<T>>) : RatingKey<T>
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
        private fun snapshotValue(value: Any?): Any? = when (value) { null, is String, is Boolean, is Byte, is Short, is Int, is Long, is Float, is Double, is java.math.BigInteger, is java.math.BigDecimal -> value; is Number -> throw IllegalArgumentException("state number values must be immutable primitives"); is Map<*, *> -> { require(value.keys.all { it is String }) { "state map keys must be strings" }; snapshot(value.entries.associate { it.key as String to it.value }) }; is Iterable<*> -> frozen(value.map { snapshotValue(it) }); is Array<*> -> frozen(value.map { snapshotValue(it) }); else -> throw IllegalArgumentException("state values must be scalar, map, or collection") }
        private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    }
    companion object { @JvmStatic fun builder() = Builder() }
}
private class RequestToken(val data: RequestData) : DecisionRequest
private data class PreparedSupportData(override val id: String, override val label: String) : PreparedSupport
private data class PreparedQuestionData(override val id: String, override val kind: DecisionKind, override val question: String, override val support: List<PreparedSupport>) : PreparedQuestion
private data class PreparedData(override val state: Map<String, Any?>, override val questions: List<PreparedQuestion>, override val deadlineNanos: Long, override val recordPolicy: DecisionRecordPolicy, override val correlationId: String?, override val requestId: String, override val questionFingerprint: String, private val clock: () -> Long) : PreparedDecisionRequest { override fun remainingNanos(): Long = (deadlineNanos - clock()).coerceAtLeast(0) }
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
        val prepared = try { prepare(request, defaultTimeout, defaultPolicy, clock(), clock) } catch (_: RuntimeException) { return failed(CallFailure.RejectedRequest, null) }
        val raw = try { invokeWithinDeadline(prepared) } catch (_: InterruptedException) { Thread.currentThread().interrupt(); return failed(CallFailure.Cancelled, prepared) } ?: return failed(CallFailure.DeadlineExceeded, prepared)
        if (remaining(prepared) == 0L) return failed(CallFailure.DeadlineExceeded, prepared)
        raw.callFailure?.let { return failed(it, prepared) }; val input = raw.answers ?: return failed(CallFailure.RejectedRequest, prepared); val source = raw.provenance ?: return failed(CallFailure.RejectedRequest, prepared)
        if (input.map { it.keyId }.distinct().size != input.size || input.any { answer -> prepared.questions.none { it.id == answer.keyId } }) return failed(CallFailure.RejectedRequest, prepared)
        val data = requestData(request); val answers = data.questions.associate { question -> question.key to validate(question, input.firstOrNull { it.keyId == question.id }) }; val provenance = completed(source, prepared)
        return DecisionOutcome.Success(provenance, safeRecord(prepared.recordPolicy, provenance, answers, prepared.questions.size), Collections.unmodifiableMap(answers))
    }
    private fun invokeWithinDeadline(prepared: PreparedDecisionRequest): RawDecisionOutcome? {
        if (provider is CallerBoundDecisionProvider) return try { provider.invoke(prepared) } catch (_: RuntimeException) { RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE) }
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
    private fun failed(failure: CallFailure, request: PreparedDecisionRequest?): DecisionOutcome { val canonical = code(failure); val provenance = facadeProvenance(request); return DecisionOutcome.Failure(failure, canonical, provenance, request?.let { safeRecord(it.recordPolicy, provenance, emptyMap(), it.questions.size, canonical) }) }
    private fun requestData(request: DecisionRequest) = (request as? RequestToken)?.data ?: throw IllegalArgumentException("unknown decision request")
    private fun prepare(request: DecisionRequest, timeoutDefault: Duration, policyDefault: DecisionRecordPolicy, start: Long, clock: () -> Long): PreparedDecisionRequest { val data = requestData(request); val timeout = data.timeout ?: timeoutDefault; validDuration(timeout); val nanos = try { timeout.toNanos() } catch (_: ArithmeticException) { throw IllegalArgumentException("timeout too large") }; val deadline = start + nanos; val questions = data.questions.map { question -> val support = if (question.kind == DecisionKind.YES_NO) listOf(PreparedSupportData("false", "false"), PreparedSupportData("true", "true")) else question.options.map { PreparedSupportData(it.id, it.label) }; PreparedQuestionData(question.id, question.kind, question.text, immutableList(support)) }; return PreparedData(data.state, immutableList(questions), deadline, data.policy ?: policyDefault, data.correlationId, UUID.randomUUID().toString(), fingerprint(questions), clock) }
    private fun remaining(request: PreparedDecisionRequest): Long = request.remainingNanos()
    private fun safeRecord(policy: DecisionRecordPolicy, provenance: DecisionProvenance, answers: Map<DecisionKey<*>, KeyOutcome<*>>, questionCount: Int, callSafeCode: DecisionSafeCode? = null): DecisionRecord? {
        if (policy.mode == RecordMode.NONE) return null
        val max = policy.maxBytes ?: MAX_METADATA_BYTES
        val fields = linkedMapOf("envelopeVersion" to "1", "schemaVersion" to "1", "provider" to provenance.provider, "requestId" to (provenance.requestId ?: ""), "questionFingerprint" to (provenance.questionFingerprint ?: ""), "questionCount" to questionCount.toString())
        provenance.correlationId?.let { fields["correlationId"] = it }
        val failures = callSafeCode?.let(::listOf) ?: answers.values.filterIsInstance<KeyOutcome.Failure<*>>().map { it.safeCode }
        if (failures.isNotEmpty()) fields["safeCodes"] = failures.joinToString(",") { it.name }
        if (policy.mode == RecordMode.FULL && "answerIds" in policy.allowlist) fields["answerIds"] = answers.values.filterIsInstance<KeyOutcome.Success<*>>().joinToString(",") { it.selectedSupportId() }
        val bounded = linkedMapOf<String, String>()
        fields.forEach { (key, value) ->
            val candidate = LinkedHashMap(bounded).apply { put(key, value) }
            if (serializedSafeRecord(candidate).size <= max) bounded[key] = value
        }
        return SafeRecord(policy.mode, Collections.unmodifiableMap(bounded))
    }
    private fun serializedSafeRecord(fields: Map<String, String>): ByteArray = fields.entries.joinToString(",", "{", "}") { (key, value) -> "${jsonString(key)}:${jsonString(value)}" }.toByteArray(StandardCharsets.UTF_8)
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
    private fun facadeProvenance(request: PreparedDecisionRequest?) = DecisionProvenance.builder("decision-facade", EvidenceKind.VERBALIZED).requestId(request?.requestId ?: UUID.randomUUID().toString()).apply { request?.correlationId?.let { correlationId(it) }; request?.questionFingerprint?.let { questionFingerprint(it) } }.build()
    private fun completed(source: DecisionProvenance, request: PreparedDecisionRequest) = DecisionProvenance.builder(source.provider, source.evidenceKind).apply { source.requestedModel?.let { requestedModel(it) }; source.resolvedModel?.let { resolvedModel(it) }; source.modelVersion?.let { modelVersion(it) }; source.adapterVersion?.let { adapterVersion(it) }; source.promptVersion?.let { promptVersion(it) }; requestId(request.requestId); request.correlationId?.let { correlationId(it) }; questionFingerprint(request.questionFingerprint); source.timestamp?.let { timestamp(it) }; source.usage?.let { usage(it.inputTokens ?: 0, it.outputTokens ?: 0) } }.build()
    private fun validDuration(value: Duration) { require(!value.isNegative && !value.isZero) { "timeout must be positive" }; try { value.toNanos() } catch (_: ArithmeticException) { throw IllegalArgumentException("timeout too large") } }
    private fun <T> immutableList(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    private fun code(failure: CallFailure) = when (failure) { CallFailure.Disabled -> DecisionSafeCode.DISABLED; CallFailure.Unavailable -> DecisionSafeCode.UNAVAILABLE; CallFailure.RejectedRequest -> DecisionSafeCode.REJECTED_REQUEST; CallFailure.DeadlineExceeded -> DecisionSafeCode.DEADLINE_EXCEEDED; CallFailure.Cancelled -> DecisionSafeCode.CANCELLED; CallFailure.Unsupported -> DecisionSafeCode.UNSUPPORTED }
    private fun code(failure: KeyFailure) = when (failure) { KeyFailure.Missing -> DecisionSafeCode.MISSING; KeyFailure.Invalid -> DecisionSafeCode.INVALID; KeyFailure.Unsupported -> DecisionSafeCode.UNSUPPORTED }
    private fun fingerprint(questions: List<PreparedQuestionData>): String = MessageDigest.getInstance("SHA-256").digest(questions.joinToString("") { question -> listOf(question.kind.name, question.id, *question.support.map { it.id }.toTypedArray()).joinToString("") { value -> "${value.toByteArray(StandardCharsets.UTF_8).size}:$value|" } }.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

private data class RequestData(val binding: String, val state: Map<String, Any?>, val questions: List<QuestionData<*>>, val timeout: Duration?, val policy: DecisionRecordPolicy?, val correlationId: String?)
private data class QuestionData<T>(val key: DecisionKey<T>, val kind: DecisionKind, val options: List<DecisionOption<T>>) { val id get() = key.id; val text get() = when (key) { is YesNoToken -> key.question; is ChoiceToken<*> -> key.question; is RatingToken<*> -> key.question; else -> error("foreign key") } }
private const val MAX_RECORD_BYTES = 1_048_576
private const val MAX_METADATA_BYTES = 4_096
