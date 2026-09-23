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
import org.slf4j.LoggerFactory
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private class YesNoToken(override val id: String, val binding: String, val question: String) : YesNoKey
private class ChoiceToken<T>(override val id: String, val binding: String, val question: String, val options: List<DecisionOption<T>>) : ChoiceKey<T>
private class RatingToken<T>(override val id: String, val binding: String, val question: String, val options: List<DecisionOption<T>>) : RatingKey<T>

/**
 * Immutable input to one [DecisionModel.ask] call.
 *
 * Build the request and retain the returned typed keys: each key is bound to this request and is
 * the only supported way to read its validated answer from a successful outcome. Timeout and
 * record policy can be set per request; otherwise the model defaults apply.
 */
@ApiStatus.Experimental
interface DecisionRequest {
    @ApiStatus.Experimental class Builder {
        private val binding = UUID.randomUUID().toString(); private val questions = mutableListOf<QuestionData<*>>()
        private var state: Map<String, Any?> = emptyMap(); private var timeout: Duration? = null; private var policy: DecisionRecordPolicy? = null; private var correlationId: String? = null
        fun state(values: Map<String, Any?>) = apply { state = DecisionRequestLimits.snapshotState(values) }
        fun correlationId(correlationId: String) = apply { this.correlationId = DecisionRequestLimits.correlationId(correlationId) }
        fun timeout(timeout: Duration) = apply { require(!timeout.isNegative && !timeout.isZero); try { timeout.toNanos() } catch (_: ArithmeticException) { throw IllegalArgumentException("timeout too large") }; this.timeout = timeout }
        fun recordPolicy(policy: DecisionRecordPolicy) = apply { this.policy = policy }
        fun yesNo(id: String, question: String): YesNoKey { question(id, question); return YesNoToken(id, binding, question).also { questions += QuestionData(it, DecisionKind.YES_NO, emptyList()) } }
        fun <T> choice(id: String, question: String, options: List<DecisionOption<T>>): ChoiceKey<T> { question(id, question); options(options); return ChoiceToken(id, binding, question, frozen(options)).also { questions += QuestionData(it, DecisionKind.CHOICE, it.options) } }
        fun <T> rating(id: String, question: String, levels: List<DecisionOption<T>>): RatingKey<T> { question(id, question); options(levels); return RatingToken(id, binding, question, frozen(levels)).also { questions += QuestionData(it, DecisionKind.RATING, it.options) } }
        fun build(): DecisionRequest { DecisionRequestLimits.validateQuestionCounts(questions.size, questions.map { if (it.kind == DecisionKind.YES_NO) 2 else it.options.size }); return RequestToken(RequestData(binding, state, frozen(questions), timeout, policy, correlationId)) }
        private fun question(id: String, question: String) { require(questions.size < DecisionRequestLimits.MAX_QUESTIONS) { "request questions exceed ${DecisionRequestLimits.MAX_QUESTIONS}" }; require(id.isNotBlank() && id.toByteArray(StandardCharsets.UTF_8).size <= 256 && id.none { it.isWhitespace() }) { "key id must be a nonblank opaque token" }; require(question.isNotBlank()) { "question must not be blank" }; DecisionRequestLimits.string(question, "question"); require(questions.none { it.id == id }) { "duplicate key id" } }
        private fun <T> options(options: List<DecisionOption<T>>) { require(options.isNotEmpty()) { "support must not be empty" }; require(options.size <= DecisionRequestLimits.MAX_SUPPORT_PER_QUESTION) { "question support exceeds ${DecisionRequestLimits.MAX_SUPPORT_PER_QUESTION} entries" }; require(options.map { it.id }.distinct().size == options.size) { "duplicate support id" }; require(options.map { it.value }.distinct().size == options.size) { "support values must be distinct" } }
        private fun <T> frozen(values: List<T>): List<T> = Collections.unmodifiableList(ArrayList(values))
    }
    companion object { @JvmStatic fun builder() = Builder() }
}
private class RequestToken(val data: RequestData) : DecisionRequest
private data class PreparedSupportData(override val id: String, override val label: String) : PreparedSupport
private data class PreparedQuestionData(override val id: String, override val kind: DecisionKind, override val question: String, override val support: List<PreparedSupport>) : PreparedQuestion
private data class PreparedData(override val state: Map<String, Any?>, override val questions: List<PreparedQuestion>, override val deadlineNanos: Long, override val recordPolicy: DecisionRecordPolicy, override val correlationId: String?, override val requestId: String, override val questionFingerprint: String, private val clock: () -> Long, private val eventSink: (DecisionTelemetryEvent) -> Unit) : PreparedDecisionRequest {
    override fun remainingNanos(): Long = (deadlineNanos - clock()).coerceAtLeast(0)
    override fun event(event: DecisionTelemetryEvent) = eventSink(event)
}
private data class SafeRecord(override val mode: RecordMode, override val fields: Map<String, String>) : DecisionRecord
private const val DEFAULT_NAME = "decision"
private const val DEFAULT_PROVIDER = "custom"
private val decisionLogger = LoggerFactory.getLogger(DecisionModel::class.java)

private sealed interface InstrumentationSelection {
    val instrumentation: DecisionInstrumentation

    data object Unset : InstrumentationSelection {
        override val instrumentation = DecisionInstrumentation.noop()
    }

    class Default(override val instrumentation: DecisionInstrumentation) : InstrumentationSelection
    class Explicit(override val instrumentation: DecisionInstrumentation) : InstrumentationSelection
}
/**
 * Final facade that prepares a request, bounds provider execution, validates raw evidence, and
 * returns typed outcomes without exposing provider-specific wire data.
 *
 * One absolute timeout covers preparation, queueing, provider work, and validation. Expected call
 * failures are returned as [DecisionOutcome.Failure]; caller interruption returns Cancelled and
 * restores the interrupt flag. Call [close] when an externally backed model is no longer used.
 * Closing interrupts active provider calls best effort and rejects later external-provider work.
 */
@ApiStatus.Experimental
class DecisionModel private constructor(
    private val decisionProvider: DecisionProvider,
    val name: String,
    val provider: String,
    private var defaultTimeout: Duration,
    private var defaultPolicy: DecisionRecordPolicy,
    private var clock: () -> Long,
    initialInstrumentation: InstrumentationSelection,
    initialExecutionContext: DecisionExecutionContext?,
) : AutoCloseable {
    constructor(decisionProvider: DecisionProvider) : this(
        decisionProvider = decisionProvider,
        name = DEFAULT_NAME,
        provider = DEFAULT_PROVIDER,
        defaultTimeout = Duration.ofSeconds(30),
        defaultPolicy = DecisionRecordPolicy.metadata(),
        clock = System::nanoTime,
        initialInstrumentation = InstrumentationSelection.Unset,
        initialExecutionContext = null,
    )

    private val execution = DecisionExecutionSupport()
    private val instrumentation = AtomicReference(initialInstrumentation)
    private val executionContext = AtomicReference(initialExecutionContext)

    fun withDefaults(defaultTimeout: Duration, defaultRecordPolicy: DecisionRecordPolicy): DecisionModel {
        validDuration(defaultTimeout)
        return DecisionModel(
            decisionProvider,
            name,
            provider,
            defaultTimeout,
            defaultRecordPolicy,
            clock,
            instrumentation.get(),
            executionContext.get(),
        )
    }

    fun named(name: String, provider: String): DecisionModel {
        require(name.isNotBlank()) { "name must not be blank" }
        require(provider.isNotBlank()) { "provider must not be blank" }
        return DecisionModel(
            decisionProvider,
            name,
            provider,
            defaultTimeout,
            defaultPolicy,
            clock,
            instrumentation.get(),
            executionContext.get(),
        )
    }

    @ApiStatus.Experimental
    fun withInstrumentation(instrumentation: DecisionInstrumentation): DecisionModel =
        DecisionModel(
            decisionProvider,
            name,
            provider,
            defaultTimeout,
            defaultPolicy,
            clock,
            InstrumentationSelection.Explicit(instrumentation),
            executionContext.get(),
        )

    /** Installs one startup default without replacing this facade or its execution capacity. */
    @ApiStatus.Experimental
    fun installDefaultInstrumentation(instrumentation: DecisionInstrumentation): Boolean =
        this.instrumentation.compareAndSet(
            InstrumentationSelection.Unset,
            InstrumentationSelection.Default(instrumentation),
        )

    /** Installs the first execution-context carrier without replacing this facade. */
    @ApiStatus.Experimental
    fun installExecutionContext(context: DecisionExecutionContext): Boolean =
        executionContext.compareAndSet(null, context)

    fun ask(request: DecisionRequest): DecisionOutcome {
        val startedAt = clock()
        val family = providerFamily(provider)
        val questionCount = (request as? RequestToken)?.data?.questions?.size ?: 0
        val selectedInstrumentation = instrumentation.get().instrumentation
        val selectedExecutionContext = executionContext.get() ?: IdentityDecisionExecutionContext
        val observation = startObservation(
            selectedInstrumentation,
            DecisionObservationContext.create(family, questionCount),
        )
        decisionLogger.debug(
            "Decision started providerFamily={} questionCount={}",
            family,
            questionCount,
        )
        var result: DecisionCallResult? = null
        var fatalError: Error? = null
        try {
            result = executeDecision(request, startedAt, observation, selectedExecutionContext)
            return result.outcome
        } catch (error: Error) {
            fatalError = error
            throw error
        } finally {
            val completion = completion(family, result, fatalError, elapsedNanos(startedAt, clock()))
            try {
                completeObservation(observation, completion)
            } finally {
                try {
                    closeObservation(observation)
                } finally {
                    logCompletion(completion)
                }
            }
        }
    }

    private fun executeDecision(
        request: DecisionRequest,
        startedAt: Long,
        observation: GuardedDecisionObservation,
        executionContext: DecisionExecutionContext,
    ): DecisionCallResult {
        val prepared = try {
            prepare(request, defaultTimeout, defaultPolicy, startedAt, clock, observation::providerEventSafely)
        } catch (_: RuntimeException) {
            observation.facadeEventSafely(DecisionTelemetryEvent.REJECTION)
            return failedResult(CallFailure.RejectedRequest, null)
        }
        val raw = try {
            invokeWithinDeadline(prepared, observation, executionContext)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            observation.facadeEventSafely(DecisionTelemetryEvent.CANCELLATION)
            return failedResult(CallFailure.Cancelled, prepared)
        } ?: run {
            observation.facadeEventSafely(DecisionTelemetryEvent.TIMEOUT)
            return failedResult(CallFailure.DeadlineExceeded, prepared)
        }
        if (remaining(prepared) == 0L) {
            observation.facadeEventSafely(DecisionTelemetryEvent.TIMEOUT)
            return failedResult(CallFailure.DeadlineExceeded, prepared)
        }
        raw.callFailure?.let {
            return failedResult(it, prepared)
        }
        val input = raw.answers ?: run {
            observation.facadeEventSafely(DecisionTelemetryEvent.REJECTION)
            return failedResult(CallFailure.RejectedRequest, prepared)
        }
        val source = raw.provenance ?: run {
            observation.facadeEventSafely(DecisionTelemetryEvent.REJECTION)
            return failedResult(CallFailure.RejectedRequest, prepared)
        }
        if (input.map { it.keyId }.distinct().size != input.size || input.any { answer -> prepared.questions.none { it.id == answer.keyId } }) {
            observation.facadeEventSafely(DecisionTelemetryEvent.REJECTION)
            return failedResult(CallFailure.RejectedRequest, prepared)
        }
        val data = requestData(request)
        val answers = data.questions.associate { question ->
            question.key to validate(question, input.firstOrNull { it.keyId == question.id })
        }
        val provenance = completed(source, prepared)
        val successCount = answers.values.count { it is KeyOutcome.Success<*> }
        val failureCount = answers.size - successCount
        return DecisionCallResult(
            DecisionOutcome.Success(
                provenance,
                safeRecord(prepared.recordPolicy, provenance, answers, prepared.questions.size),
                Collections.unmodifiableMap(answers),
            ),
            successCount,
            failureCount,
        )
    }

    private fun invokeWithinDeadline(
        prepared: PreparedDecisionRequest,
        observation: GuardedDecisionObservation,
        executionContext: DecisionExecutionContext,
    ): RawDecisionOutcome? {
        val providerWork = Callable {
            try {
                decisionProvider.invoke(prepared)
            } finally {
                observation.sealProviderEvents()
            }
        }
        val work = executionContext.wrapSafely(observation.wrapSafely(providerWork))
            ?: return RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE)
        try {
            if (decisionProvider is CallerBoundDecisionProvider) {
                return try {
                    work.call()
                } catch (error: InterruptedException) {
                    throw error
                } catch (_: Exception) {
                    RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE)
                }
            }
            val future = try {
                execution.submit(work)
            } catch (_: RejectedExecutionException) {
                observation.sealProviderEvents()
                val event = if (execution.isClosed) {
                    DecisionTelemetryEvent.MODEL_CLOSED
                } else {
                    DecisionTelemetryEvent.CAPACITY_REJECTED
                }
                observation.facadeEventSafely(event)
                return RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE)
            }
            return try {
                future.get(remaining(prepared), TimeUnit.NANOSECONDS)
            } catch (_: TimeoutException) {
                observation.sealProviderEvents()
                future.cancel(true)
                null
            } catch (error: InterruptedException) {
                observation.sealProviderEvents()
                future.cancel(true)
                throw error
            } catch (error: ExecutionException) {
                when (val cause = error.cause) {
                    is InterruptedException -> {
                        observation.facadeEventSafely(DecisionTelemetryEvent.CANCELLATION)
                        RawDecisionOutcome.failure(CallFailure.Cancelled, DecisionSafeCode.CANCELLED)
                    }
                    is Error -> throw cause
                    else -> RawDecisionOutcome.failure(CallFailure.Unavailable, DecisionSafeCode.UNAVAILABLE)
                }
            }
        } finally {
            observation.sealProviderEvents()
        }
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
    private fun failedResult(failure: CallFailure, request: PreparedDecisionRequest?) =
        DecisionCallResult(failed(failure, request), 0, 0)
    private fun requestData(request: DecisionRequest) = (request as? RequestToken)?.data ?: throw IllegalArgumentException("unknown decision request")
    private fun prepare(request: DecisionRequest, timeoutDefault: Duration, policyDefault: DecisionRecordPolicy, start: Long, clock: () -> Long, eventSink: (DecisionTelemetryEvent) -> Unit): PreparedDecisionRequest { val data = requestData(request); val timeout = data.timeout ?: timeoutDefault; validDuration(timeout); val nanos = try { timeout.toNanos() } catch (_: ArithmeticException) { throw IllegalArgumentException("timeout too large") }; val deadline = start + nanos; val questions = data.questions.map { question -> val support = if (question.kind == DecisionKind.YES_NO) listOf(PreparedSupportData("false", "false"), PreparedSupportData("true", "true")) else question.options.map { PreparedSupportData(it.id, it.label) }; PreparedQuestionData(question.id, question.kind, question.text, immutableList(support)) }; val immutableQuestions = immutableList<PreparedQuestion>(questions); DecisionRequestLimits.validatePreparedBytes(data.state, immutableQuestions); return PreparedData(data.state, immutableQuestions, deadline, data.policy ?: policyDefault, data.correlationId, UUID.randomUUID().toString(), fingerprint(questions), clock, eventSink) }
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
    override fun close() {
        execution.close()
        decisionLogger.debug("Decision execution closed")
    }

}

private data class DecisionCallResult(
    val outcome: DecisionOutcome,
    val keySuccessCount: Int,
    val keyFailureCount: Int,
)

private enum class InstrumentationHook { START, WRAP, WRAPPED_WORK, EVENT, COMPLETE, CLOSE }

private fun startObservation(
    instrumentation: DecisionInstrumentation,
    context: DecisionObservationContext,
): GuardedDecisionObservation {
    val observation = try {
        requireNotNull(instrumentation.start(context))
    } catch (_: Exception) {
        logInstrumentationFailure(InstrumentationHook.START)
        DecisionInstrumentation.noop().start(context)
    }
    return GuardedDecisionObservation(observation)
}

private class GuardedDecisionObservation(
    private val delegate: DecisionObservation,
) : DecisionObservation {
    private val terminal = AtomicBoolean()
    private val providerEventsOpen = AtomicBoolean(true)

    override fun <T> wrap(work: Callable<T>): Callable<T> = delegate.wrap(work)

    override fun event(event: DecisionTelemetryEvent) {
        facadeEventSafely(event)
    }

    override fun complete(completion: DecisionCompletion) {
        terminal.set(true)
        delegate.complete(completion)
    }

    override fun close() {
        terminal.set(true)
        delegate.close()
    }

    fun sealProviderEvents() {
        providerEventsOpen.set(false)
    }

    fun providerEventSafely(event: DecisionTelemetryEvent) {
        if (providerEventsOpen.get()) deliverSafely(event)
    }

    fun facadeEventSafely(event: DecisionTelemetryEvent) {
        if (!terminal.get()) deliverSafely(event)
    }

    private fun deliverSafely(event: DecisionTelemetryEvent) {
        try {
            decisionLogger.debug("Decision event={}", event)
            delegate.event(event)
        } catch (_: Exception) {
            logInstrumentationFailure(InstrumentationHook.EVENT)
        }
    }
}

private enum class ExecutionContextPhase { WRAP, CALL }

private interface InstrumentedDecisionWork<T> : Callable<T> {
    fun arm()
    fun wasInvoked(): Boolean
    fun failure(): Throwable?
    fun replay(): T
}

private fun <T> DecisionExecutionContext.wrapSafely(work: InstrumentedDecisionWork<T>): Callable<T>? {
    val deferred = DeferredCallable(work)
    val wrapped = try {
        requireNotNull(wrap(deferred))
    } catch (error: Exception) {
        if (error is InterruptedException) throw error
        logExecutionContextFailure(ExecutionContextPhase.WRAP)
        return null
    }
    work.arm()
    // Arm the carrier-facing guard last so eager wrap calls cannot enter instrumentation.
    deferred.arm()
    return Callable {
        var contextFailure: Throwable? = null
        try {
            wrapped.call()
        } catch (error: Throwable) {
            contextFailure = error
        }
        val workFailure = work.failure()
        // Preserve provider fatality; otherwise a carrier Error must outrank ordinary failures.
        if (workFailure is Error) {
            if (contextFailure is Error && contextFailure !== workFailure) {
                workFailure.addSuppressed(contextFailure)
            }
            throw workFailure
        }
        if (contextFailure is Error) {
            // The fatal wrapper wins, but consumed interruption still belongs to this thread.
            if (workFailure is InterruptedException) Thread.currentThread().interrupt()
            workFailure?.let(contextFailure::addSuppressed)
            logExecutionContextFailure(ExecutionContextPhase.CALL)
            throw contextFailure
        }
        workFailure?.let { throw it }
        contextFailure?.let { failure ->
            logExecutionContextFailure(ExecutionContextPhase.CALL)
            throw failure
        }
        if (!work.wasInvoked()) {
            logExecutionContextFailure(ExecutionContextPhase.CALL)
            throw IllegalStateException("execution context did not invoke work")
        }
        work.replay()
    }
}

private fun <T> DecisionObservation.wrapSafely(work: Callable<T>): InstrumentedDecisionWork<T> {
    val once = OnceCallable(work)
    val deferred = DeferredCallable(once)
    val wrapped = try {
        requireNotNull(wrap(deferred))
    } catch (error: Exception) {
        if (error is InterruptedException) throw error
        logInstrumentationFailure(InstrumentationHook.WRAP)
        deferred
    }
    return object : InstrumentedDecisionWork<T> {
        override fun call(): T {
            var wrapperFailure: Exception? = null
            try {
                wrapped.call()
            } catch (error: Exception) {
                if (error is InterruptedException && !once.wasInvoked()) throw error
                wrapperFailure = error
            }
            val workFailure = once.failure()
            if (!once.wasInvoked() || wrapperFailure != null && wrapperFailure !== workFailure ||
                wrapperFailure == null && workFailure != null
            ) {
                logInstrumentationFailure(InstrumentationHook.WRAPPED_WORK)
            }
            if (wrapperFailure is InterruptedException && wrapperFailure !== workFailure) {
                Thread.currentThread().interrupt()
            }
            return deferred.call()
        }

        override fun arm() = deferred.arm()
        override fun wasInvoked(): Boolean = once.wasInvoked()
        override fun failure(): Throwable? = once.failure()
        override fun replay(): T = once.call()
    }
}

private class DeferredCallable<T>(private val work: Callable<T>) : Callable<T> {
    private val armed = AtomicBoolean()

    override fun call(): T {
        check(armed.get()) { "execution context is not established" }
        return work.call()
    }

    fun arm() {
        armed.set(true)
    }
}

private class OnceCallable<T>(private val work: Callable<T>) : Callable<T> {
    private var result: Result<T>? = null

    @Synchronized
    override fun call(): T {
        result?.let { return it.getOrThrow() }
        return try {
            work.call().also { result = Result.success(it) }
        } catch (error: Throwable) {
            result = Result.failure(error)
            throw error
        }
    }

    @Synchronized
    fun wasInvoked(): Boolean = result != null

    @Synchronized
    fun failure(): Throwable? = result?.exceptionOrNull()
}

private fun completeObservation(observation: DecisionObservation, completion: DecisionCompletion) {
    try {
        observation.complete(completion)
    } catch (_: Exception) {
        logInstrumentationFailure(InstrumentationHook.COMPLETE)
    }
}

private fun closeObservation(observation: DecisionObservation) {
    try {
        observation.close()
    } catch (_: Exception) {
        logInstrumentationFailure(InstrumentationHook.CLOSE)
    }
}

private fun logInstrumentationFailure(hook: InstrumentationHook) {
    decisionLogger.warn("Decision instrumentation hook failed hook={}", hook)
}

private fun logExecutionContextFailure(phase: ExecutionContextPhase) {
    decisionLogger.warn("Decision execution wrapper failed phase={}", phase)
}

private fun providerFamily(provider: String): DecisionProviderFamily = when (provider) {
    "typesafe" -> DecisionProviderFamily.TYPESAFE
    "prompted" -> DecisionProviderFamily.PROMPTED
    "none" -> DecisionProviderFamily.NONE
    "stub" -> DecisionProviderFamily.STUB
    else -> DecisionProviderFamily.CUSTOM
}

private fun completion(
    family: DecisionProviderFamily,
    result: DecisionCallResult?,
    fatalError: Error?,
    elapsedNanos: Long,
): DecisionCompletion {
    val outcome = result?.outcome
    val status = when {
        fatalError != null -> DecisionCompletionStatus.FAILURE
        outcome is DecisionOutcome.Success -> DecisionCompletionStatus.SUCCESS
        else -> DecisionCompletionStatus.FAILURE
    }
    return DecisionCompletion.create(
        family,
        status,
        (outcome as? DecisionOutcome.Failure)?.safeCode,
        result?.keySuccessCount ?: 0,
        result?.keyFailureCount ?: 0,
        elapsedNanos,
    )
}

private fun elapsedNanos(startedAt: Long, completedAt: Long): Long = (completedAt - startedAt).coerceAtLeast(0)

private fun logCompletion(completion: DecisionCompletion) {
    val message = "Decision completed providerFamily={} status={} safeCode={} keySuccessCount={} keyFailureCount={} elapsedNanos={}"
    val arguments = arrayOf<Any?>(
        completion.providerFamily,
        completion.status,
        completion.safeCode,
        completion.keySuccessCount,
        completion.keyFailureCount,
        completion.elapsedNanos,
    )
    if (completion.status == DecisionCompletionStatus.SUCCESS ||
        completion.safeCode == DecisionSafeCode.DISABLED ||
        completion.safeCode == DecisionSafeCode.UNSUPPORTED
    ) {
        decisionLogger.debug(message, *arguments)
    } else {
        decisionLogger.warn(message, *arguments)
    }
}

private data class RequestData(val binding: String, val state: Map<String, Any?>, val questions: List<QuestionData<*>>, val timeout: Duration?, val policy: DecisionRecordPolicy?, val correlationId: String?)
private data class QuestionData<T>(val key: DecisionKey<T>, val kind: DecisionKind, val options: List<DecisionOption<T>>) { val id get() = key.id; val text get() = when (key) { is YesNoToken -> key.question; is ChoiceToken<*> -> key.question; is RatingToken<*> -> key.question; else -> error("foreign key") } }
private const val MAX_RECORD_BYTES = 1_048_576
private const val MAX_METADATA_BYTES = 4_096
