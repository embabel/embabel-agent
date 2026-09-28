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
package com.embabel.common.ai.decision.spi

import com.embabel.common.ai.classification.ClassificationRequest
import com.embabel.common.ai.classification.ClassificationResult
import com.embabel.common.ai.classification.ClassificationSpec
import com.embabel.common.ai.classification.FailureReason
import com.embabel.common.ai.decision.ChoiceQuestionSpec
import com.embabel.common.ai.decision.DecisionAnswer
import com.embabel.common.ai.decision.DecisionCapabilities
import com.embabel.common.ai.decision.DecisionRequest
import com.embabel.common.ai.decision.DecisionResponse
import com.embabel.common.ai.decision.DecisionService
import com.embabel.common.ai.decision.PropositionQuestionSpec
import com.embabel.common.ai.decision.PropositionRequest
import com.embabel.common.ai.decision.PropositionResult
import com.embabel.common.ai.decision.Question
import com.embabel.common.ai.decision.QuestionKind
import com.embabel.common.ai.decision.RatingQuestionSpec
import com.embabel.common.ai.decision.RatingResult
import com.embabel.common.ai.decision.UnsupportedDecisionException
import org.slf4j.LoggerFactory
import java.util.EnumSet
import java.util.concurrent.CancellationException

/**
 * Plans and runs decision requests for decision services.
 *
 * Planning is a preflight over the whole request. It checks question kinds and limits, and throws
 * before any provider call when the service cannot answer the request. A service that implements
 * [NativeQuestionSetExecution] answers the whole request in one call. Any other service answers
 * each question on its own, in spec order: a choice question through `classify`, and the other
 * kinds through their per-question hooks. Execution logs start and completion
 * at DEBUG, failed requests, partial responses and answer anomalies at WARN, and request and
 * response content at TRACE only when [DecisionContentCapture] is on.
 */
internal object DecisionExecution {

    private val logger = LoggerFactory.getLogger(DecisionExecution::class.java)

    /**
     * The capabilities of a decision service that implements none of the hooks: proposition and
     * choice questions, with no reported limits. Such a service answers each proposition through
     * `assess` and each choice through `classify`.
     */
    val LEGACY_CAPABILITIES: DecisionCapabilities =
        DecisionCapabilities.of(EnumSet.of(QuestionKind.PROPOSITION, QuestionKind.CHOICE))

    /**
     * Returns the capabilities a service reports when it does not override them. They derive from
     * the hooks the source implements: [QuestionKind.PROPOSITION] and [QuestionKind.CHOICE] always,
     * and [QuestionKind.RATING] with [RatingAssessment]. Every decision service can classify, so it
     * answers a choice question through `classify`. A service that answers other kinds through
     * [NativeQuestionSetExecution] overrides its capabilities to list them.
     *
     * @param hookSource the object whose hook interfaces are inspected
     * @return the derived capabilities, with no limits
     */
    fun defaultCapabilities(hookSource: Any): DecisionCapabilities {
        val kinds = EnumSet.of(QuestionKind.PROPOSITION, QuestionKind.CHOICE)
        if (hookSource is RatingAssessment) kinds += QuestionKind.RATING
        return DecisionCapabilities.of(kinds)
    }

    /**
     * Checks a request against a service before any provider call, and reports whether the service
     * answers it in one native call.
     *
     * The checks run in this order: every question kind is in the capabilities, the request is
     * within the reported limits, every question has a backing, then [service] implements every hook
     * the request is routed through. A service that implements [NativeQuestionSetExecution] backs
     * every kind it claims. Otherwise a proposition question is backed by [PropositionAssessment] or
     * `assess`, a choice question by `classify` and a rating question by [RatingAssessment].
     * Routing follows the hooks of [hookSource], and execution calls those hooks on [service]. The
     * last check makes a decorator that leaves out one of its hook source's hooks fail here, before
     * any question is asked.
     *
     * @param serviceName the service name used in messages
     * @param capabilities the capabilities the service reports
     * @param hookSource the object whose hook interfaces decide the routing
     * @param request the request to plan
     * @param service the object whose hook methods execution calls
     * @return true when the service answers the request in one native call
     * @throws UnsupportedDecisionException if a question kind or a limit rules the request out
     * @throws IllegalStateException if the capabilities claim a question kind that the service backs
     * with neither its hook nor native execution, or if [service] lacks a hook of [hookSource] that
     * the request is routed through
     */
    fun plan(
        serviceName: String,
        capabilities: DecisionCapabilities,
        hookSource: Any,
        request: DecisionRequest,
        service: Any = hookSource,
    ): Boolean {
        val questions = request.spec.questions
        val rejection = Rejection(serviceName, capabilities)
        checkKinds(questions, capabilities, hookSource, rejection)
        checkLimits(request, capabilities, rejection)
        if (hookSource is NativeQuestionSetExecution) {
            checkForwarded(serviceName, service, hookSource, listOf(NativeQuestionSetExecution::class.java))
            return true
        }
        for (question in questions) {
            check(hasHook(hookSource, question.kind)) {
                val hook = requiredHook(question.kind)
                "Decision service '$serviceName' claims ${question.kind.name.lowercase()} questions but implements " +
                    "neither $hook nor NativeQuestionSetExecution. Implement $hook, or remove ${question.kind} " +
                    "from its capabilities."
            }
        }
        val routedHooks = questions.mapNotNull { question ->
            when (question.kind) {
                QuestionKind.PROPOSITION -> if (hookSource is PropositionAssessment) PropositionAssessment::class.java else null
                QuestionKind.CHOICE -> null
                QuestionKind.RATING -> RatingAssessment::class.java
            }
        }.distinct()
        checkForwarded(serviceName, service, hookSource, routedHooks)
        return false
    }

    /**
     * True when the hook source backs one question of the kind in per-question execution.
     * Propositions are always backed, by PropositionAssessment or by assess. Choices are always
     * backed by classify.
     *
     * @param hookSource the object whose hook interfaces are inspected
     * @param kind the question kind to check
     * @return true when the hook source backs that kind
     */
    private fun hasHook(hookSource: Any, kind: QuestionKind): Boolean = when (kind) {
        QuestionKind.PROPOSITION, QuestionKind.CHOICE -> true
        QuestionKind.RATING -> hookSource is RatingAssessment
    }

    /**
     * Routing reads the hook source's interfaces and execution casts the service to them. A
     * decorator that reports a hook source must implement each hook the request is routed through.
     *
     * @param serviceName the service name used in messages
     * @param service the object execution casts to the hooks
     * @param hookSource the object whose hook interfaces decide routing
     * @param hooks the hook interfaces the request is routed through
     */
    private fun checkForwarded(serviceName: String, service: Any, hookSource: Any, hooks: List<Class<*>>) {
        if (service === hookSource) return
        val missing = hooks.filterNot { it.isInstance(service) }
        if (missing.isEmpty()) return
        val names = missing.joinToString(" and ") { it.simpleName }
        val serviceClass = service.javaClass.name
        val sourceName = (hookSource as? DecisionService)?.name?.let { "'$it' " }.orEmpty()
        throw IllegalStateException(
            "Decision service '$serviceName' ($serviceClass) reports hook source $sourceName" +
                "(${hookSource.javaClass.name}), which implements $names, but $serviceClass does not implement " +
                "$names. Execution calls these hooks on the decorator. Implement $names on $serviceClass and " +
                "forward the calls to the hook source.",
        )
    }

    /**
     * A missing kind gets one of two explanations. When the hook source lacks the per-question hook
     * for the kind, the question needs that hook. Otherwise the service can answer the kind and its
     * capabilities leave it out, so the remedy is to report the kind in capabilities().
     *
     * @param questions the request's questions
     * @param capabilities the capabilities the service reports
     * @param hookSource the object whose hook interfaces decide routing
     * @param rejection builds the exception when a kind is unsupported
     */
    private fun checkKinds(
        questions: List<Question<*>>,
        capabilities: DecisionCapabilities,
        hookSource: Any,
        rejection: Rejection,
    ) {
        val unsupported = questions.filter { it.kind !in capabilities.questionKinds }
        if (unsupported.isEmpty()) return
        val native = hookSource is NativeQuestionSetExecution
        fun needsHook(kind: QuestionKind) = !native && !hasHook(hookSource, kind)
        val missing = unsupported.map { it.kind }.distinct()
        val hookless = missing.filter(::needsHook)
        val unlisted = missing.filterNot(::needsHook)
        val details = unsupported.joinToString(", ") { question ->
            val kind = question.kind
            val label = "'${question.name}' ($kind)"
            when {
                needsHook(kind) -> "$label needs ${requiredHook(kind)}"
                native -> label
                else -> "$label, which the service backs with ${backingName(hookSource, kind)}"
            }
        }
        val remedies = buildList {
            if (unlisted.isNotEmpty()) add("report ${unlisted.joinToString(" and ")} in the service's capabilities()")
            add(
                if (hookless.isNotEmpty()) {
                    "use a service that implements ${hookless.joinToString(" and ") { requiredHook(it) }}"
                } else {
                    "use a service whose capabilities include ${unlisted.joinToString(" and ")}"
                },
            )
        }
        throw rejection.of(
            unsupported,
            "the service's capabilities leave out ${missing.joinToString(" and ")} questions: $details",
            (remedies.joinToString(", ") + ", or remove these questions.").replaceFirstChar { it.uppercase() },
        )
    }

    /**
     * The method that answers one question of a kind the hook source backs.
     *
     * @param hookSource the object whose hook interfaces are inspected
     * @param kind the question kind
     * @return the hook method's name
     */
    private fun backingName(hookSource: Any, kind: QuestionKind): String = when (kind) {
        QuestionKind.PROPOSITION -> if (hookSource is PropositionAssessment) "PropositionAssessment" else "assess"
        QuestionKind.CHOICE -> "classify"
        QuestionKind.RATING -> "RatingAssessment"
    }

    /**
     * Checks the request against the service's question count and input length limits, and throws
     * when either is exceeded.
     *
     * @param request the request to check
     * @param capabilities the capabilities the service reports
     * @param rejection builds the exception when a limit is exceeded
     */
    private fun checkLimits(request: DecisionRequest, capabilities: DecisionCapabilities, rejection: Rejection) {
        val questions = request.spec.questions
        capabilities.maxQuestions?.let { limit ->
            if (questions.size > limit) {
                throw rejection.of(
                    questions,
                    "the request has ${questions.size} questions, above maxQuestions $limit",
                    "Split the request into requests of at most $limit questions, or use a service with a higher limit.",
                )
            }
        }
        capabilities.maxInputCharacters?.let { limit ->
            if (request.input.length > limit) {
                throw rejection.of(
                    questions,
                    "the input has ${request.input.length} characters, above maxInputCharacters $limit",
                    "Shorten the input to at most $limit characters, or use a service with a higher limit.",
                )
            }
        }
    }

    /**
     * The hook a service must implement to answer one question of a kind on its own. Only rating
     * needs one: propositions fall back to assess and choices go through classify.
     *
     * @param kind the question kind
     * @return the hook interface's name
     */
    private fun requiredHook(kind: QuestionKind): String = when (kind) {
        QuestionKind.PROPOSITION -> "PropositionAssessment"
        QuestionKind.CHOICE -> "classify"
        QuestionKind.RATING -> "RatingAssessment"
    }

    /**
     * Runs a request on a service. Preflight runs first and may throw before any provider call.
     *
     * The service supplies the capabilities and receives the calls. The hook source is inspected
     * for the hook interfaces. It defaults to the service's own hook source when the service is a
     * [DelegatingDecisionService], and to the service otherwise, so preflight sees the hooks of the
     * service that does the work. Preflight also checks that the service implements each hook the
     * request is routed through. In per-question execution, typed failures
     * are recorded and execution continues with the next question. A choice question goes to
     * `classify` as a classification request built from the question, so the provider sees the
     * question's own instructions and categories. An [IllegalArgumentException] from `classify` or
     * a rating hook, or from validating its answer, becomes that question's
     * `INVALID_RESPONSE` failure. Any other thrown exception stops the request and propagates
     * unchanged. Per-question execution checks the thread's interrupt flag before each question and
     * stops with an unchecked [CancellationException] caused by an [InterruptedException] when it is
     * set, leaving the flag set.
     *
     * @param service the service whose capabilities apply and whose methods are called
     * @param request the request to run
     * @param hookSource the object whose hook interfaces preflight inspects
     * @return the response, in spec order
     * @throws UnsupportedDecisionException if the service cannot run the request
     * @throws IllegalStateException if the capabilities claim a hook the hook source lacks, if the
     * service lacks a hook of the hook source that the request is routed through, or if a native
     * response does not match the request's spec
     * @throws CancellationException if the thread is interrupted between questions; its cause is an
     * [InterruptedException] and the flag stays set
     */
    fun execute(
        service: DecisionService,
        request: DecisionRequest,
        hookSource: Any = hookSourceOf(service),
    ): DecisionResponse {
        val native = plan(service.name, service.capabilities(), hookSource, request, service)
        val spec = request.spec
        if (logger.isDebugEnabled) {
            logger.debug(
                "Decision ask started: service={}, provider={}, questions={}",
                service.name, service.provider, spec.questions.size,
            )
        }
        if (DecisionContentCapture.isEnabled() && logger.isTraceEnabled) {
            logger.trace(
                "Decision request content: service={}, input={}, questions=[{}]",
                service.name, request.input, spec.questions.joinToString("; ", transform = ::describeQuestion),
            )
        }
        val started = System.nanoTime()
        val response = if (native) {
            runNative(service as NativeQuestionSetExecution, service, request)
        } else {
            runPerQuestion(service, request, propositionHook = hookSource is PropositionAssessment)
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        logOutcome(service, response, elapsedMs)
        if (DecisionContentCapture.isEnabled() && logger.isTraceEnabled) {
            logger.trace(
                "Decision response content: service={}, answers=[{}]",
                service.name, response.answers.joinToString("; ") { "'${it.name}' ${outcomeOf(it)}" },
            )
        }
        return response
    }

    /**
     * Returns the service whose hooks do the work for [service]: the hook source of a
     * [DelegatingDecisionService], or the service itself.
     */
    fun hookSourceOf(service: DecisionService): DecisionService =
        (service as? DelegatingDecisionService)?.hookSource ?: service

    /**
     * Answers the whole request in one native call and checks that the response matches the spec.
     *
     * @param hook the native execution hook to call
     * @param service the service, named in an error message
     * @param request the request to run
     * @return the native response
     */
    private fun runNative(
        hook: NativeQuestionSetExecution,
        service: DecisionService,
        request: DecisionRequest,
    ): DecisionResponse {
        val response = hook.askNative(request)
        try {
            response.requireMatches(request.spec)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException(
                "Decision service '${service.name}' returned a native response that does not answer the " +
                    "request's spec. ${e.message} The service's askNative implementation must answer the request's spec.",
                e,
            )
        }
        return response
    }

    /**
     * Answers each question of the request in turn: a choice through classify, the other kinds
     * through their per-question hooks.
     *
     * @param service the service whose hook methods are called
     * @param request the request to run
     * @param propositionHook true when the hook source implements PropositionAssessment
     * @return the response, in spec order
     */
    private fun runPerQuestion(service: DecisionService, request: DecisionRequest, propositionHook: Boolean): DecisionResponse {
        val builder = DecisionResponse.builder(request.spec)
        val input = request.input
        for (question in request.spec.questions) {
            if (Thread.currentThread().isInterrupted) {
                logger.debug(
                    "Decision ask interrupted: service={}, provider={}, nextQuestion='{}'",
                    service.name, service.provider, question.name,
                )
                val message = "Decision ask on service '${service.name}' was interrupted before question " +
                    "'${question.name}'. The thread's interrupt flag is set."
                throw CancellationException(message).apply { initCause(InterruptedException(message)) }
            }
            when (question) {
                is PropositionQuestionSpec -> builder.answer(
                    question,
                    if (propositionHook) {
                        (service as PropositionAssessment).assess(input, question)
                    } else {
                        service.assess(PropositionRequest(input, question.instructions))
                    },
                )

                is ChoiceQuestionSpec -> builder.answer(
                    question,
                    validated(service, question, ClassificationResult.Failure(FailureReason.INVALID_RESPONSE)) {
                        question.validate(service.classify(ClassificationRequest.of(input, ClassificationSpec.of(question))))
                    },
                )

                is RatingQuestionSpec -> builder.answer(
                    question,
                    validated(service, question, RatingResult.Failure(FailureReason.INVALID_RESPONSE)) {
                        question.validate((service as RatingAssessment).rate(input, question))
                    },
                )
            }
        }
        return builder.build()
    }

    /**
     * Guards a classify or rating call and its validation. An IllegalArgumentException from either
     * means the answer does not fit the question: decorators validate inside classify and their hook
     * methods, and throw it for an option or level outside the question. Other exceptions propagate.
     *
     * @param service the service, named in the anomaly log line
     * @param question the question, named in the anomaly log line
     * @param failure the result to use when the answer is out of domain
     * @param validate makes the call and validates its answer
     * @return the validated answer, or the failure result when it's out of domain
     */
    private inline fun <R> validated(service: DecisionService, question: Question<*>, failure: R, validate: () -> R): R =
        try {
            validate()
        } catch (invalid: IllegalArgumentException) {
            logger.warn(
                "Decision answer anomaly: service={}, question='{}', anomaly=OUT_OF_DOMAIN. The answer does not fit " +
                    "the question's options or levels and is recorded as INVALID_RESPONSE. Check the service's " +
                    "mapping of provider output to the question.",
                service.name, question.name,
            )
            failure
        }

    /**
     * Logs a decision's outcome: a failed request at WARN, partial failures at WARN, and completion at DEBUG.
     *
     * @param service the service, named in the log lines
     * @param response the response to describe
     * @param elapsedMs how long the request took
     */
    private fun logOutcome(service: DecisionService, response: DecisionResponse, elapsedMs: Long) {
        val requestFailure = response.requestFailure
        if (requestFailure != null) {
            logger.warn(
                "Decision ask failed: service={}, provider={}, requestFailure={}, elapsedMs={}. " +
                    "The provider's own log lines give the cause.",
                service.name, service.provider, requestFailure, elapsedMs,
            )
        } else {
            val failed = response.answers.mapNotNull { answer -> failureOf(answer)?.let { "'${answer.name}' $it" } }
            if (failed.isNotEmpty()) {
                logger.warn(
                    "Decision ask partially failed: service={}, provider={}, failedQuestions=[{}], elapsedMs={}. " +
                        "The other answers are usable. The provider's own log lines give the cause.",
                    service.name, service.provider, failed.joinToString(", "), elapsedMs,
                )
            }
        }
        if (logger.isDebugEnabled) {
            logger.debug(
                "Decision ask completed: service={}, provider={}, answers=[{}], elapsedMs={}",
                service.name, service.provider,
                response.answers.joinToString(", ") { "'${it.name}' ${it.kind} ${outcomeName(it)}" },
                elapsedMs,
            )
        }
    }

    /**
     * Returns the failure reason of an answer, or null when it isn't a failure.
     *
     * @param answer the answer to inspect
     * @return the failure reason, or null
     */
    private fun failureOf(answer: DecisionAnswer): FailureReason? = when (answer) {
        is DecisionAnswer.Proposition -> (answer.outcome as? PropositionResult.Failure)?.reason
        is DecisionAnswer.Choice -> (answer.outcome as? ClassificationResult.Failure)?.reason
        is DecisionAnswer.Rating -> (answer.outcome as? RatingResult.Failure)?.reason
    }

    /**
     * The outcome's variant only, so DEBUG lines hold no evidence or provider text.
     *
     * @param answer the answer to describe
     * @return a short name for the outcome
     */
    private fun outcomeName(answer: DecisionAnswer): String = when (val outcome = outcomeOf(answer)) {
        is PropositionResult.Answered, is RatingResult.Answered -> "answered"
        is ClassificationResult.Selected -> "selected"
        is ClassificationResult.NoMatch -> "no_match"
        is PropositionResult.Inconclusive, is ClassificationResult.Inconclusive, is RatingResult.Inconclusive -> "inconclusive"
        else -> "failure ${failureOf(answer) ?: outcome}"
    }

    /**
     * Returns the answer's outcome, regardless of its question kind.
     *
     * @param answer the answer to unwrap
     * @return the outcome
     */
    private fun outcomeOf(answer: DecisionAnswer): Any = when (answer) {
        is DecisionAnswer.Proposition -> answer.outcome
        is DecisionAnswer.Choice -> answer.outcome
        is DecisionAnswer.Rating -> answer.outcome
    }

    /**
     * Content for TRACE capture only: instructions and option or level text.
     *
     * @param question the question to describe
     * @return the description for the trace line
     */
    private fun describeQuestion(question: Question<*>): String = when (question) {
        is PropositionQuestionSpec -> "'${question.name}' ${question.kind} instructions=${question.instructions}"
        is ChoiceQuestionSpec -> "'${question.name}' ${question.kind} instructions=${question.instructions} " +
            "options=${question.options.joinToString { "${it.id}: ${it.description}" }}"
        is RatingQuestionSpec -> "'${question.name}' ${question.kind} instructions=${question.instructions} " +
            "levels=${question.levels.joinToString { "${it.id}: ${it.description}" }}"
    }

    // Builds rejection messages. They hold question names and kinds, never input or question text.
    private class Rejection(
        private val serviceName: String,
        private val capabilities: DecisionCapabilities,
    ) {
        /**
         * Builds the exception for a rejected request.
         *
         * @param questions the questions the rejection concerns
         * @param reason why the request is rejected
         * @param remedy what to do about it
         * @return the exception to throw
         */
        fun of(questions: List<Question<*>>, reason: String, remedy: String): UnsupportedDecisionException =
            UnsupportedDecisionException(
                "Decision service '$serviceName' cannot run this request: $reason. " +
                    "Questions: ${questions.joinToString { "'${it.name}' (${it.kind})" }}. " +
                    "Service capabilities: kinds ${capabilities.questionKinds}, " +
                    "maxQuestions ${capabilities.maxQuestions ?: "not reported"}, " +
                    "maxInputCharacters ${capabilities.maxInputCharacters ?: "not reported"}. $remedy",
            )
    }
}
