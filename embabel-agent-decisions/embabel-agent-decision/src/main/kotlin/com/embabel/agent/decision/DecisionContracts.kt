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
import java.util.UUID

@ApiStatus.Experimental
enum class DecisionKind { YES_NO, CHOICE, RATING }

@ApiStatus.Experimental
enum class EvidenceKind { DISTRIBUTION, VERBALIZED }

@ApiStatus.Experimental
enum class RecordMode { NONE, METADATA, FULL }

@ApiStatus.Experimental
enum class DecisionSafeCode {
    DISABLED, UNAVAILABLE, REJECTED_REQUEST, DEADLINE_EXCEEDED, CANCELLED, UNSUPPORTED, MISSING, INVALID
}

@ApiStatus.Experimental
enum class CallFailure { Disabled, Unavailable, RejectedRequest, DeadlineExceeded, Cancelled, Unsupported }

@ApiStatus.Experimental
enum class KeyFailure { Missing, Invalid, Unsupported }

@ApiStatus.Experimental
class DecisionOption<T> private constructor(
    val id: String,
    val value: T,
    val label: String,
) {
    init {
        requireOpaqueId(id, "support id")
        require(label.isNotBlank()) { "label must not be blank" }
    }

    companion object {
        @JvmStatic
        fun <T> of(id: String, value: T, label: String): DecisionOption<T> = DecisionOption(id, value, label)
    }
}

/** A request-bound token. Values can only be read through a successful [DecisionOutcome]. */
@ApiStatus.Experimental
sealed interface DecisionKey<T> {
    val id: String
}

@ApiStatus.Experimental
class YesNoKey private constructor(
    override val id: String,
    internal val binding: String,
    internal val question: String,
) : DecisionKey<Boolean> {
    companion object { @JvmSynthetic internal fun create(id: String, binding: String, question: String) = YesNoKey(id, binding, question) }
}

@ApiStatus.Experimental
class ChoiceKey<T> private constructor(
    override val id: String,
    internal val binding: String,
    internal val question: String,
    internal val options: List<DecisionOption<T>>,
) : DecisionKey<T> {
    companion object { @JvmSynthetic internal fun <T> create(id: String, binding: String, question: String, options: List<DecisionOption<T>>) = ChoiceKey(id, binding, question, options) }
}

@ApiStatus.Experimental
class RatingKey<T> private constructor(
    override val id: String,
    internal val binding: String,
    internal val question: String,
    internal val options: List<DecisionOption<T>>,
) : DecisionKey<T> {
    companion object { @JvmSynthetic internal fun <T> create(id: String, binding: String, question: String, options: List<DecisionOption<T>>) = RatingKey(id, binding, question, options) }
}

@ApiStatus.Experimental
class DecisionRecordPolicy private constructor(
    val mode: RecordMode,
    val maxBytes: Int?,
    val allowlist: Set<String>,
) {
    companion object {
        @JvmStatic fun none(): DecisionRecordPolicy = DecisionRecordPolicy(RecordMode.NONE, null, emptySet())
        @JvmStatic fun metadata(): DecisionRecordPolicy = DecisionRecordPolicy(RecordMode.METADATA, null, emptySet())

        @JvmStatic
        fun full(maxBytes: Int, allowlist: Set<String>): DecisionRecordPolicy {
            require(maxBytes in 1..MAX_RECORD_BYTES) { "maxBytes must be between 1 and $MAX_RECORD_BYTES" }
            require(allowlist.isNotEmpty()) { "full records require an explicit allowlist" }
            val copied = allowlist.map {
                require(it.isNotBlank() && it != "*" && !it.contains('*')) { "allowlist entries must be explicit" }
                it
            }.toSet()
            return DecisionRecordPolicy(RecordMode.FULL, maxBytes, copied)
        }
    }
}

@ApiStatus.Experimental
class DecisionRequest private constructor(
    internal val binding: String,
    internal val state: Map<String, Any?>,
    internal val entries: List<Entry<*>>,
    internal val requestTimeout: Duration?,
    internal val requestedRecordPolicy: DecisionRecordPolicy?,
    internal val requestedCorrelationId: String?,
) {
    @ApiStatus.Experimental
    class Builder {
        private val binding = UUID.randomUUID().toString()
        private val entries = mutableListOf<Entry<*>>()
        private var state: Map<String, Any?> = emptyMap()
        private var timeout: Duration? = null
        private var policy: DecisionRecordPolicy? = null
        private var correlationId: String? = null

        fun state(values: Map<String, Any?>): Builder = apply { state = immutableState(values) }

        fun correlationId(correlationId: String): Builder = apply {
            validateCorrelationId(correlationId)
            this.correlationId = correlationId
        }

        fun timeout(timeout: Duration): Builder = apply {
            require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
            this.timeout = timeout
        }

        fun recordPolicy(policy: DecisionRecordPolicy): Builder = apply { this.policy = policy }

        fun yesNo(id: String, question: String): YesNoKey {
            validateEntry(id, question)
            val key = YesNoKey.create(id, binding, question)
            entries += Entry.YesNo(key)
            return key
        }

        fun <T> choice(id: String, question: String, options: List<DecisionOption<T>>): ChoiceKey<T> {
            validateEntry(id, question)
            validateOptions(options)
            val key = ChoiceKey.create(id, binding, question, options.toList())
            entries += Entry.Choice(key)
            return key
        }

        fun <T> rating(id: String, question: String, levels: List<DecisionOption<T>>): RatingKey<T> {
            validateEntry(id, question)
            validateOptions(levels)
            val key = RatingKey.create(id, binding, question, levels.toList())
            entries += Entry.Rating(key)
            return key
        }

        fun build(): DecisionRequest {
            require(entries.isNotEmpty()) { "a decision request needs at least one question" }
            return DecisionRequest(binding, state, entries.toList(), timeout, policy, correlationId)
        }

        private fun validateEntry(id: String, question: String) {
            requireOpaqueId(id, "key id")
            require(question.isNotBlank()) { "question must not be blank" }
            require(entries.none { it.key.id == id }) { "duplicate key id '$id'" }
        }

        private fun <T> validateOptions(options: List<DecisionOption<T>>) {
            require(options.isNotEmpty()) { "support must not be empty" }
            require(options.map { it.id }.distinct().size == options.size) { "duplicate support id" }
        }
    }

    companion object {
        @JvmStatic fun builder(): Builder = Builder()
    }

    internal sealed interface Entry<T> {
        val key: DecisionKey<T>
        val kind: DecisionKind

        class YesNo(override val key: YesNoKey) : Entry<Boolean> { override val kind = DecisionKind.YES_NO }
        class Choice<T>(override val key: ChoiceKey<T>) : Entry<T> { override val kind = DecisionKind.CHOICE }
        class Rating<T>(override val key: RatingKey<T>) : Entry<T> { override val kind = DecisionKind.RATING }
    }
}

/** Immutable, redacted provider input. Its construction stays inside the facade. */
@ApiStatus.Experimental
class PreparedDecisionRequest private constructor(
    val state: Map<String, Any?>,
    val questions: List<PreparedQuestion>,
    val deadlineNanos: Long,
    val recordPolicy: DecisionRecordPolicy,
    val correlationId: String?,
    val requestId: String,
    val questionFingerprint: String,
) {
    companion object {
        private fun create(
            state: Map<String, Any?>,
            questions: List<PreparedQuestion>,
            deadlineNanos: Long,
            recordPolicy: DecisionRecordPolicy,
            correlationId: String?,
            requestId: String,
            fingerprint: String,
        ) = PreparedDecisionRequest(state, questions, deadlineNanos, recordPolicy, correlationId, requestId, fingerprint)

        internal fun fromFacade(
            request: DecisionRequest,
            defaultTimeout: Duration,
            defaultPolicy: DecisionRecordPolicy,
            nowNanos: Long,
        ): PreparedDecisionRequest {
            val timeout = request.requestTimeout ?: defaultTimeout
            require(!timeout.isNegative && !timeout.isZero) { "timeout must be positive" }
            val questions = request.entries.map { entry ->
                when (entry) {
                    is DecisionRequest.Entry.YesNo -> PreparedQuestion(entry.key.id, entry.key.binding, entry.kind, entry.key.question, listOf(
                        PreparedSupport("true", "true"), PreparedSupport("false", "false")
                    ))
                    is DecisionRequest.Entry.Choice<*> -> PreparedQuestion(entry.key.id, entry.key.binding, entry.kind, entry.key.question,
                        entry.key.options.map { PreparedSupport(it.id, it.label) })
                    is DecisionRequest.Entry.Rating<*> -> PreparedQuestion(entry.key.id, entry.key.binding, entry.kind, entry.key.question,
                        entry.key.options.map { PreparedSupport(it.id, it.label) })
                }
            }
            val fingerprint = questions.joinToString("|") { question ->
                "${question.kind}:${question.id}:${question.support.map { it.id }.joinToString(",")}"
            }.hashCode().toUInt().toString(16)
            return create(
                immutableState(request.state), questions.toList(), saturatingAdd(nowNanos, timeout.toNanos()),
                request.requestedRecordPolicy ?: defaultPolicy, request.requestedCorrelationId,
                UUID.randomUUID().toString(), fingerprint,
            )
        }
    }
}

@ApiStatus.Experimental
class PreparedQuestion private constructor(
    val id: String,
    internal val binding: String,
    val kind: DecisionKind,
    val question: String,
    val support: List<PreparedSupport>,
) {
    companion object {
        internal operator fun invoke(id: String, binding: String, kind: DecisionKind, question: String, support: List<PreparedSupport>) =
            PreparedQuestion(id, binding, kind, question, support.toList())
    }
}

@ApiStatus.Experimental
class PreparedSupport private constructor(val id: String, val label: String) {
    companion object {
        internal operator fun invoke(id: String, label: String) = PreparedSupport(id, label)
    }
}

@ApiStatus.Experimental
class DecisionUsage private constructor(val inputTokens: Int?, val outputTokens: Int?) {
    companion object { internal fun of(input: Int?, output: Int?) = DecisionUsage(input, output) }
}

@ApiStatus.Experimental
class DecisionProvenance private constructor(
    val provider: String,
    val evidenceKind: EvidenceKind,
    val requestedModel: String?,
    val resolvedModel: String?,
    val modelVersion: String?,
    val adapterVersion: String?,
    val promptVersion: String?,
    val requestId: String?,
    val correlationId: String?,
    val questionFingerprint: String?,
    val timestamp: Instant?,
    val usage: DecisionUsage?,
) {
    @ApiStatus.Experimental
    class Builder internal constructor(private val provider: String, private val evidenceKind: EvidenceKind) {
        private var requestedModel: String? = null; private var resolvedModel: String? = null
        private var modelVersion: String? = null; private var adapterVersion: String? = null; private var promptVersion: String? = null
        private var requestId: String? = null; private var correlationId: String? = null; private var questionFingerprint: String? = null
        private var timestamp: Instant? = null; private var usage: DecisionUsage? = null
        fun requestedModel(value: String) = apply { requestedModel = optional(value) }
        fun resolvedModel(value: String) = apply { resolvedModel = optional(value) }
        fun modelVersion(value: String) = apply { modelVersion = optional(value) }
        fun adapterVersion(value: String) = apply { adapterVersion = optional(value) }
        fun promptVersion(value: String) = apply { promptVersion = optional(value) }
        fun requestId(value: String) = apply { requestId = optional(value) }
        fun correlationId(value: String) = apply { validateCorrelationId(value); correlationId = value }
        fun questionFingerprint(value: String) = apply { questionFingerprint = optional(value) }
        fun timestamp(value: Instant) = apply { timestamp = value }
        fun usage(inputTokens: Int, outputTokens: Int) = apply {
            require(inputTokens >= 0 && outputTokens >= 0) { "usage must be nonnegative" }
            usage = DecisionUsage.of(inputTokens, outputTokens)
        }
        fun build() = DecisionProvenance(provider, evidenceKind, requestedModel, resolvedModel, modelVersion, adapterVersion,
            promptVersion, requestId, correlationId, questionFingerprint, timestamp, usage)
    }
    companion object {
        @JvmStatic fun builder(provider: String, evidenceKind: EvidenceKind): Builder {
            require(provider.isNotBlank()) { "provider must not be blank" }
            return Builder(provider, evidenceKind)
        }
        internal fun completed(source: DecisionProvenance, prepared: PreparedDecisionRequest): DecisionProvenance =
            DecisionProvenance(source.provider, source.evidenceKind, source.requestedModel, source.resolvedModel, source.modelVersion,
                source.adapterVersion, source.promptVersion, source.requestId ?: prepared.requestId,
                source.correlationId ?: prepared.correlationId, source.questionFingerprint ?: prepared.questionFingerprint,
                source.timestamp, source.usage)
    }
}

@ApiStatus.Experimental
class RawProbability private constructor(val supportId: String, val probability: Double) {
    companion object { @JvmStatic fun of(supportId: String, probability: Double) = RawProbability(supportId, probability) }
}

@ApiStatus.Experimental
class RawAnswer private constructor(
    val keyId: String,
    val kind: DecisionKind?,
    val probabilities: List<RawProbability>,
    val selectedId: String?,
    val pTrue: Double?,
    val keyFailure: KeyFailure?,
    val safeCode: DecisionSafeCode?,
) {
    companion object {
        @JvmStatic fun yesNo(keyId: String, pTrue: Double, selectedId: String?) =
            RawAnswer(keyId, DecisionKind.YES_NO, emptyList(), selectedId, pTrue, null, null)
        @JvmStatic fun distribution(keyId: String, kind: DecisionKind, probabilities: List<RawProbability>, selectedId: String?) =
            RawAnswer(keyId, kind, probabilities.toList(), selectedId, null, null, null)
        @JvmStatic fun failure(keyId: String, keyFailure: KeyFailure, safeCode: DecisionSafeCode) =
            RawAnswer(keyId, null, emptyList(), null, null, keyFailure, safeCode)
    }
}

@ApiStatus.Experimental
class RawDecisionOutcome private constructor(
    val answers: List<RawAnswer>?,
    val provenance: DecisionProvenance?,
    val callFailure: CallFailure?,
    val safeCode: DecisionSafeCode?,
) {
    companion object {
        @JvmStatic fun success(answers: List<RawAnswer>, provenance: DecisionProvenance) =
            RawDecisionOutcome(answers.toList(), provenance, null, null)
        @JvmStatic fun failure(callFailure: CallFailure, safeCode: DecisionSafeCode) =
            RawDecisionOutcome(null, null, callFailure, safeCode)
    }
}

@ApiStatus.Experimental
fun interface DecisionProvider { fun invoke(request: PreparedDecisionRequest): RawDecisionOutcome }

@ApiStatus.Experimental
class DecisionRecord private constructor(val mode: RecordMode, val fields: Map<String, String>) {
    companion object { @JvmSynthetic internal fun create(mode: RecordMode, fields: Map<String, String>) = DecisionRecord(mode, fields.toMap()) }
}

@ApiStatus.Experimental
sealed class KeyOutcome<T> {
    @ApiStatus.Experimental
    class Success<T> private constructor(
        val value: T,
        val distribution: Map<T, Double>,
        val maximizers: List<T>,
        val firstMaximizer: T,
        val expectedScore: Double?,
    ) : KeyOutcome<T>() {
        companion object { @JvmSynthetic internal fun <T> create(value: T, distribution: Map<T, Double>, maximizers: List<T>, firstMaximizer: T, expectedScore: Double?) = Success(value, distribution.toMap(), maximizers.toList(), firstMaximizer, expectedScore) }
    }

    @ApiStatus.Experimental
    class Failure<T> private constructor(val failure: KeyFailure, val safeCode: DecisionSafeCode) : KeyOutcome<T>() {
        companion object { @JvmSynthetic internal fun <T> create(failure: KeyFailure, safeCode: DecisionSafeCode) = Failure<T>(failure, safeCode) }
    }
}

@ApiStatus.Experimental
sealed class DecisionOutcome {
    @ApiStatus.Experimental
    class Success private constructor(
        val provenance: DecisionProvenance,
        val record: DecisionRecord?,
        private val binding: String,
        private val answers: Map<String, KeyOutcome<*>>,
    ) : DecisionOutcome() {
        companion object { @JvmSynthetic internal fun create(provenance: DecisionProvenance, record: DecisionRecord?, binding: String, answers: Map<String, KeyOutcome<*>>) = Success(provenance, record, binding, answers.toMap()) }
        fun <T> answer(key: DecisionKey<T>): KeyOutcome<T> {
            require(keyBinding(key) == binding) { "decision key belongs to another request" }
            @Suppress("UNCHECKED_CAST")
            return answers[key.id] as? KeyOutcome<T> ?: KeyOutcome.Failure.create(KeyFailure.Missing, DecisionSafeCode.MISSING)
        }
    }

    @ApiStatus.Experimental
    class Failure private constructor(
        val failure: CallFailure,
        val safeCode: DecisionSafeCode,
        val provenance: DecisionProvenance,
        val record: DecisionRecord?,
    ) : DecisionOutcome() {
        companion object { @JvmSynthetic internal fun create(failure: CallFailure, safeCode: DecisionSafeCode, provenance: DecisionProvenance, record: DecisionRecord?) = Failure(failure, safeCode, provenance, record) }
    }
}

internal fun requireOpaqueId(value: String, label: String) {
    require(value.isNotBlank() && value.length <= 256 && value.none { it.isWhitespace() }) { "$label must be a nonblank opaque token" }
}

internal fun validateCorrelationId(value: String) {
    require(value.isNotBlank() && value.toByteArray(StandardCharsets.UTF_8).size <= 256) { "correlationId must be nonblank and at most 256 UTF-8 bytes" }
}

private fun optional(value: String): String? = value.takeIf { it.isNotBlank() }

internal fun keyBinding(key: DecisionKey<*>): String = when (key) {
    is YesNoKey -> key.binding; is ChoiceKey<*> -> key.binding; is RatingKey<*> -> key.binding
}

internal fun immutableState(values: Map<String, Any?>): Map<String, Any?> = values.mapNotNull { (key, value) ->
    require(key.isNotBlank()) { "state keys must not be blank" }
    if (key.contains("secret", true) || key.contains("password", true) || key.contains("token", true)) null
    else key to immutableValue(value)
}.toMap()

private fun immutableValue(value: Any?): Any? = when (value) {
    null, is String, is Number, is Boolean -> value
    is Map<*, *> -> value.entries.mapNotNull { (key, nested) -> (key as? String)?.let { it to immutableValue(nested) } }.toMap()
    is Iterable<*> -> value.map { immutableValue(it) }
    is Array<*> -> value.map { immutableValue(it) }
    else -> throw IllegalArgumentException("state values must be scalar, map, or collection")
}

internal fun saturatingAdd(now: Long, duration: Long): Long = if (duration > 0 && Long.MAX_VALUE - now < duration) Long.MAX_VALUE else now + duration

internal const val MAX_RECORD_BYTES = 1_048_576
