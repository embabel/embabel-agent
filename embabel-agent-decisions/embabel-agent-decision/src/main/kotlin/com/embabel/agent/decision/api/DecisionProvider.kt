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
package com.embabel.agent.decision.api

import org.jetbrains.annotations.ApiStatus
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Collections

/**
 * The immutable caller-approved request passed to a [DecisionProvider].
 *
 * One instance exists for one [DecisionModel.ask] call. A provider may read it only for the
 * duration of [DecisionProvider.invoke] and must not retain it or any nested state, question, or
 * support value. All collections are immutable snapshots. [state] is transported exactly as the
 * caller supplies it; callers must project only data that is safe for the selected provider.
 * Providers must treat state and question text as sensitive application data.
 *
 * [deadlineNanos] is an absolute value from the facade's monotonic clock. It is meaningful only in
 * the current process and clock domain; do not compare it with wall-clock time or persist it.
 * Prefer [remainingNanos], check it before each transport or retry, and stop work when it reaches
 * zero. The facade discards late results and may interrupt the provider thread at the deadline.
 */
@ApiStatus.Experimental
interface PreparedDecisionRequest {
    val state: Map<String, Any?>
    val questions: List<PreparedQuestion>
    val deadlineNanos: Long
    val recordPolicy: DecisionRecordPolicy
    val correlationId: String?
    val requestId: String
    val questionFingerprint: String

    /** Returns time left on the same monotonic clock as [deadlineNanos], clamped to zero. */
    fun remainingNanos(): Long

    /** Emits a bounded operational event through the facade's optional instrumentation. */
    @ApiStatus.Experimental
    fun event(event: DecisionTelemetryEvent) = Unit
}

/**
 * One immutable question presented to a provider.
 *
 * The question id is opaque and unique within its request. [support] declares the complete allowed
 * result domain in request order, including `false` and `true` for a yes/no question.
 */
@ApiStatus.Experimental
interface PreparedQuestion {
    val id: String
    val kind: DecisionKind
    val question: String
    val support: List<PreparedSupport>
}

/** An immutable support id and display label declared by the caller. */
@ApiStatus.Experimental
interface PreparedSupport {
    val id: String
    val label: String
}

/** Optional provider usage metadata. Values, when present, are non-negative token counts. */
@ApiStatus.Experimental
interface DecisionUsage {
    val inputTokens: Int?
    val outputTokens: Int?
}

private data class UsageData(override val inputTokens: Int?, override val outputTokens: Int?) : DecisionUsage

/**
 * Immutable provenance supplied by a provider and completed by the facade.
 *
 * Providers identify themselves and the evidence they produced. The facade copies provider and
 * model fields, then replaces request id, correlation id, and question fingerprint with its own
 * prepared-request values. A provider should not retain request data in provenance. Text fields
 * are bounded, single-line metadata and must not contain prompts, facts, credentials, or exception
 * messages.
 */
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
    /** Mutable construction scope for one immutable [DecisionProvenance] value. Builders are not thread-safe. */
    @ApiStatus.Experimental
    interface Builder {
        fun requestedModel(value: String): Builder
        fun resolvedModel(value: String): Builder
        fun modelVersion(value: String): Builder
        fun adapterVersion(value: String): Builder
        fun promptVersion(value: String): Builder
        fun requestId(value: String): Builder
        fun correlationId(value: String): Builder
        fun questionFingerprint(value: String): Builder
        fun timestamp(value: Instant): Builder
        fun usage(inputTokens: Int, outputTokens: Int): Builder
        fun build(): DecisionProvenance
    }

    private class BuilderData(private val provider: String, private val evidenceKind: EvidenceKind) : Builder {
        private var requestedModel: String? = null
        private var resolvedModel: String? = null
        private var modelVersion: String? = null
        private var adapterVersion: String? = null
        private var promptVersion: String? = null
        private var requestId: String? = null
        private var correlationId: String? = null
        private var fingerprint: String? = null
        private var timestamp: Instant? = null
        private var usage: DecisionUsage? = null

        override fun requestedModel(value: String) = apply { requestedModel = text(value) }
        override fun resolvedModel(value: String) = apply { resolvedModel = text(value) }
        override fun modelVersion(value: String) = apply { modelVersion = text(value) }
        override fun adapterVersion(value: String) = apply { adapterVersion = text(value) }
        override fun promptVersion(value: String) = apply { promptVersion = text(value) }
        override fun requestId(value: String) = apply { requestId = text(value) }
        override fun correlationId(value: String) = apply { correlationId = DecisionRequestLimits.correlationId(value) }
        override fun questionFingerprint(value: String) = apply { fingerprint = text(value) }
        override fun timestamp(value: Instant) = apply { timestamp = value }
        override fun usage(inputTokens: Int, outputTokens: Int) = apply {
            require(inputTokens >= 0 && outputTokens >= 0)
            usage = UsageData(inputTokens, outputTokens)
        }
        override fun build() = DecisionProvenance(
            provider,
            evidenceKind,
            requestedModel,
            resolvedModel,
            modelVersion,
            adapterVersion,
            promptVersion,
            requestId,
            correlationId,
            fingerprint,
            timestamp,
            usage,
        )

        private fun text(value: String): String {
            require(value.isNotBlank() && value.toByteArray(StandardCharsets.UTF_8).size <= 256 && value.none { it == '\n' || it == '\r' })
            return value
        }
    }

    companion object {
        /** Starts a provenance value for one provider result. */
        @JvmStatic
        fun builder(provider: String, evidenceKind: EvidenceKind): Builder {
            require(provider.isNotBlank() && provider.toByteArray(StandardCharsets.UTF_8).size <= 256 && provider.none { it == '\n' || it == '\r' })
            return BuilderData(provider, evidenceKind)
        }
    }
}

/** One immutable support probability in a raw provider distribution. */
@ApiStatus.Experimental
class RawProbability private constructor(val supportId: String, val probability: Double) {
    companion object {
        /** Creates an unvalidated probability. The facade validates id membership, finiteness, and range. */
        @JvmStatic
        fun of(supportId: String, probability: Double) = RawProbability(supportId, probability)
    }
}

/**
 * Untrusted evidence for one prepared question.
 *
 * For [yesNo], `pTrue` must be finite and in `0.0..1.0`. A selected id is optional; when present it
 * must be `true` or `false` and identify a maximizer. For [distribution], probabilities must contain
 * each declared support id exactly once, contain no other id, be finite and in `0.0..1.0`, and sum
 * to one within `1e-9`. Its optional selected id must identify a maximizer. The facade resolves an
 * omitted selected id deterministically in declared support order and preserves every maximizer.
 *
 * [failure] is per-key evidence. The facade canonicalizes its safe code from [KeyFailure]: `Missing`
 * to `MISSING`, `Invalid` to `INVALID`, and `Unsupported` to `UNSUPPORTED`. Factory arguments and
 * copied collections remain raw until the facade validates them.
 */
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
        /** Creates raw yes/no evidence. The facade derives the false probability as `1 - pTrue`. */
        @JvmStatic
        fun yesNo(keyId: String, pTrue: Double, selectedId: String?) =
            RawAnswer(keyId, DecisionKind.YES_NO, emptyList(), selectedId, pTrue, null, null)

        /** Copies a raw choice or rating distribution for later facade validation. */
        @JvmStatic
        fun distribution(
            keyId: String,
            kind: DecisionKind,
            probabilities: List<RawProbability>,
            selectedId: String?,
        ) = RawAnswer(
            keyId,
            kind,
            Collections.unmodifiableList(ArrayList(probabilities)),
            selectedId,
            null,
            null,
            null,
        )

        /** Creates one key-local failure. The facade derives the final safe code from [keyFailure]. */
        @JvmStatic
        fun failure(keyId: String, keyFailure: KeyFailure, safeCode: DecisionSafeCode) =
            RawAnswer(keyId, null, emptyList(), null, null, keyFailure, safeCode)
    }
}

/**
 * Untrusted call-level result returned by a [DecisionProvider].
 *
 * A success must carry provenance. It may omit a prepared question, which becomes a per-key
 * `Missing` result, but it must not repeat an answer id or include an unknown id; either rejects the
 * complete call. A failure has no answers or provider provenance. The facade canonicalizes its safe
 * code from [CallFailure]: `Disabled` to `DISABLED`, `Unavailable` to `UNAVAILABLE`,
 * `RejectedRequest` to `REJECTED_REQUEST`, `DeadlineExceeded` to `DEADLINE_EXCEEDED`, `Cancelled`
 * to `CANCELLED`, and `Unsupported` to `UNSUPPORTED`.
 */
@ApiStatus.Experimental
class RawDecisionOutcome private constructor(
    val answers: List<RawAnswer>?,
    val provenance: DecisionProvenance?,
    val callFailure: CallFailure?,
    val safeCode: DecisionSafeCode?,
) {
    companion object {
        /** Copies a provider result. The facade still validates every answer and provenance field. */
        @JvmStatic
        fun success(answers: List<RawAnswer>, provenance: DecisionProvenance) = RawDecisionOutcome(
            Collections.unmodifiableList(ArrayList(answers)),
            provenance,
            null,
            null,
        )

        /** Creates a call failure whose supplied safe code remains untrusted until facade projection. */
        @JvmStatic
        fun failure(callFailure: CallFailure, safeCode: DecisionSafeCode) =
            RawDecisionOutcome(null, null, callFailure, safeCode)
    }
}

/**
 * Provider SPI behind the final [DecisionModel] facade.
 *
 * A provider instance can receive concurrent invocations and must be thread-safe. The facade invokes
 * it once per ask on a worker pool bounded to that [DecisionModel], applies the request's single
 * monotonic deadline, and owns
 * all schema, probability, selection, provenance, and record validation. Providers return raw
 * evidence only; they do not construct trusted outcomes or choose application actions.
 *
 * A provider should honor interruption and propagate [InterruptedException] to the facade rather
 * than changing an unrelated caller's interrupt flag. It must stop transport, retry, and parsing
 * work when [PreparedDecisionRequest.remainingNanos] reaches zero. Cancellation is best effort, so
 * providers must not publish side effects after the deadline. An uncaught exception maps to
 * call-level `Unavailable`; caller interruption maps to `Cancelled`; an overrun maps to
 * `DeadlineExceeded`.
 * Implementations must not return `null` or retain the prepared request after this method returns.
 * Close a model that is no longer used to interrupt its workers and release its execution capacity.
 */
@ApiStatus.Experimental
fun interface DecisionProvider {
    fun invoke(request: PreparedDecisionRequest): RawDecisionOutcome
}
